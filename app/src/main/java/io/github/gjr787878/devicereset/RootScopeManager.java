package io.github.gjr787878.devicereset;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Root 作用域直写管理器（零点击生效）。由 {@link FrameworkDetector} 精确判定当前框架分支后，
 * 自动选择对应方案：
 *
 * <ul>
 *   <li><b>Vector（JingMatrix）</b>：root 直接调用模块自带 {@code cli}（编译期令牌鉴权，无
 *       ADB 来源门），由守护进程在自己内存里写库 + 刷 ConfigCache，<b>无需重启、即时生效</b>。</li>
 *   <li><b>官方 LSPosed</b>：{@code lspctl} 的写作用域命令被开发者模式 + CliOriginVerifier
 *       （{@code z1.b}，要求调用进程祖先链含 adbd 且有 ADB 连接）挡住，App 无人值守无法通过，
 *       只能直写库后重启守护进程。</li>
 * </ul>
 *
 * <p>4.6.2 关键修复（针对「管理器打不开，只有重启手机才行」的根因）：</p>
 * <ul>
 *   <li>实锤根因（LSPosed 2.2.1 + Zygisk Next 日志）：lspbridge 桥接在<b>开机时由 zygote
 *       注入侧建立一次</b>；手动重启 daemon 后，运行中的 zygote/system_server 不会重连新 daemon
 *       （日志持续刷 {@code no response from bridge}，管理器启动时只能 force-stop shell 再拉起
 *       却永远拿不到服务）。官方框架自身的恢复逻辑在双 ABI 设备上只会
 *       {@code ctl.restart zygote_secondary}（32 位副 zygote），不重启承载 system_server 的
 *       主 zygote，因此桥接永久断裂。只有 zygote 重新注入（手机重启/框架软重启）才能恢复。</li>
 *   <li>官方分支新流程：{@code lspctl stop} → 写库 → 先拉起新 daemon（创建 lspbridge 套接字）
 *       → {@code setprop ctl.restart zygote}（+zygote_secondary）触发<b>框架软重启</b>：
 *       不重启手机、约 1 分钟，zygote 与 system_server 全部重建、zygisk 重新注入、桥接重建；
 *       若属性被 SELinux 拒绝，兜底 SIGKILL 主 zygote（init 会自动重启）。</li>
 *   <li>软重启会杀死所有应用进程（含本 App），故完整流程由 {@code setsid} 脱离 App 进程组的
 *       独立脚本 {@code /data/local/tmp/drs_apply.sh} 执行，结果写入 marker 文件；
 *       App 重启后读 marker 判定成功/失败。</li>
 *   <li>用户选择「稍后」时走 4.6.1 的进程内 daemon 重启路径（管理器需等下次手机重启才能打开，
 *       界面明确提示），不再作为默认路径。</li>
 * </ul>
 *
 * <p>4.6.1 保留的可靠性措施：</p>
 * <ul>
 *   <li>所有框架操作（写作用域、ensureDaemon）由进程内静态锁 + 状态机串行化；</li>
 *   <li>停机优先用官方公开命令 {@code lspctl stop} 优雅退出，超时才 TERM/KILL；</li>
 *   <li>新库在守护进程<b>完全停止后</b>才拷回；</li>
 *   <li>就绪轮询要求连续 3 次 {@code lspctl status} 成功。</li>
 * </ul>
 *
 * <p>作用域中系统框架只写 {@code system}（加载时 packageName 才是 {@code android}）。
 * 只操作本模块自己的行，其他模块完全不受影响。</p>
 */
public final class RootScopeManager {

    private static final String TAG = "RootScopeManager";
    private static final String MODULE_PKG = "io.github.gjr787878.devicereset";
    private static final int USER_ID = 0;
    private static final String LIVE_DB = "/data/adb/lspd/config/modules_config.db";

    /** 4.6.2 框架软重启独立脚本（setsid 脱离 App，App 被 zygote 重启杀死后仍执行）。 */
    public static final String APPLY_SCRIPT_PATH = "/data/local/tmp/drs_apply.sh";
    /** 软重启结果 marker：START / DB_WRITTEN / CTRL_RESTART_SENT / RESTART_OK / RESTART_FAIL。 */
    public static final String REBOOT_MARKER_PATH = "/data/local/tmp/drs_soft_reboot.marker";
    /** 软重启脚本日志。 */
    public static final String APPLY_LOG_PATH = "/data/local/tmp/drs_soft_reboot.log";

    /** 等价于 android.database.sqlite.DISABLE_WAL（@hide 常量，数值 0x02000000）。 */
    private static final int DISABLE_WAL = 0x02000000;

    // ====================== 全局状态机（跨 Activity，进程内串行） ======================
    /** 空闲：无框架操作。 */
    public static final int STATE_IDLE = 0;
    /** 应用中：正在写库/重启守护进程（读状态与二次写入都必须避让）。 */
    public static final int STATE_APPLYING = 1;
    private static final Object STATE_LOCK = new Object();
    private static volatile int state = STATE_IDLE;
    /** 最近一次由本模块重启守护进程的时间戳；冷却期内 ensureDaemon 不重复拉起。 */
    private static volatile long lastRestartMs = 0L;
    /** 重启后冷却时长（ms）：就绪轮询最长约 100s，留足余量。 */
    private static final long POST_RESTART_COOLDOWN_MS = 120_000L;

    /** 是否有作用域写入正在进行（写库/重启窗口）。 */
    public static boolean isApplying() {
        synchronized (STATE_LOCK) {
            return state == STATE_APPLYING;
        }
    }

    private static void setStateIdle() {
        synchronized (STATE_LOCK) {
            state = STATE_IDLE;
        }
    }

    private final Context context;
    private final int appUid;
    private final File stageDir;
    private final File stageDb;
    private final String SDB;
    private final String SWAL;
    private final String SSHM;
    private final String BACKUP_DB;

    private Boolean rootCached = null;

    public RootScopeManager(Context context) {
        this.context = context.getApplicationContext();
        this.appUid = android.os.Process.myUid();
        this.stageDir = new File(this.context.getFilesDir(), "drs_root");
        this.stageDb = new File(stageDir, "modules_config.db");
        this.SDB = stageDb.getAbsolutePath();
        this.SWAL = SDB + "-wal";
        this.SSHM = SDB + "-shm";
        this.BACKUP_DB = new File(stageDir, "live_backup.db").getAbsolutePath();
    }

    // ============================== root shell ==============================

    public static final class Result {
        public final int exitCode;
        public final String output;
        Result(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
        public boolean ok() { return exitCode == 0; }
    }

    /** 以 root 运行脚本（多行），返回退出码与输出。 */
    public static Result runRoot(String script) {
        Process p = null;
        StringBuilder out = new StringBuilder();
        try {
            p = Runtime.getRuntime().exec(new String[]{"su"});
            OutputStream os = p.getOutputStream();
            os.write((script + "\nexit\n").getBytes());
            os.flush();
            os.close();
            Thread tOut = stream(p, out, false);
            Thread tErr = stream(p, out, true);
            int code = p.waitFor();
            if (tOut != null) tOut.join(1500);
            if (tErr != null) tErr.join(1500);
            return new Result(code, out.toString().trim());
        } catch (Throwable t) {
            Log.e(TAG, "runRoot failed", t);
            return new Result(-1, String.valueOf(t.getMessage()));
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static Thread stream(final Process p, final StringBuilder sb, final boolean err) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    err ? p.getErrorStream() : p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sb) { sb.append(line).append('\n'); }
                }
            } catch (Throwable ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** root 是否可用（结果缓存）。 */
    public boolean isRootAvailable() {
        if (rootCached != null) return rootCached;
        Result r = runRoot("id");
        rootCached = r.ok() && r.output.contains("uid=0");
        return rootCached;
    }

    // ============================== 拷出（root → filesDir） ==============================

    /** root 把 db(+wal/shm) 拷到私有暂存目录，并 chown/restorecon 让本应用可读写；同时备份 live 库。 */
    private boolean stageFromDevice(String db) {
        String script =
                "rm -rf '" + stageDir.getAbsolutePath() + "'\n" +
                "mkdir -p '" + stageDir.getAbsolutePath() + "'\n" +
                "cp '" + db + "' '" + SDB + "' 2>&1\n" +
                "[ -f '" + db + "-wal' ] && cp '" + db + "-wal' '" + SWAL + "' 2>&1\n" +
                "[ -f '" + db + "-shm' ] && cp '" + db + "-shm' '" + SSHM + "' 2>&1\n" +
                "cp '" + db + "' '" + BACKUP_DB + "' 2>&1\n" +
                "chmod 666 '" + SDB + "' '" + BACKUP_DB + "' 2>/dev/null\n" +
                "[ -f '" + SWAL + "' ] && chmod 666 '" + SWAL + "' 2>/dev/null\n" +
                "chown -R " + appUid + ":" + appUid + " '" + stageDir.getAbsolutePath() + "' 2>&1\n" +
                "restorecon -RF '" + stageDir.getAbsolutePath() + "' 2>&1\n" +
                "ls -la '" + stageDir.getAbsolutePath() + "'\n" +
                "echo STAGED";
        Result r = runRoot(script);
        Log.i(TAG, "stageFromDevice: exit=" + r.exitCode + " output=" + r.output);
        boolean ok = r.output.contains("STAGED") && stageDb.exists() && stageDb.canRead();
        Log.i(TAG, "stageFromDevice: ok=" + ok + " exists=" + stageDb.exists()
                + " canRead=" + stageDb.canRead());
        return ok;
    }

    // ============================== 表结构检测 ==============================

    /**
     * 检测数据库表结构版本：
     *   2 = 新版：modules(mid,...) + scope(mid,...)
     *   1 = 旧版：modules(module_pkg_name, apk_path) + modules_state + scope(module_pkg_name,...)
     *   0 = 未知/无 scope
     */
    private int detectSchema(SQLiteDatabase sql) {
        try (Cursor c = sql.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('modules','modules_state','scope')",
                null)) {
            boolean hasModules = false, hasScope = false;
            while (c.moveToNext()) {
                String n = c.getString(0);
                if ("modules".equals(n)) hasModules = true;
                if ("scope".equals(n)) hasScope = true;
            }
            if (!hasScope) return 0;
            if (hasModules) {
                try (Cursor cc = sql.rawQuery("PRAGMA table_info(modules)", null)) {
                    while (cc.moveToNext()) {
                        if ("mid".equals(cc.getString(1))) return 2;
                    }
                }
            }
            return 1;
        }
    }

    // ============================== 读取作用域 ==============================

    /** 读取本模块当前作用域包名集合。失败返回 null。应用中（重启窗口）调用直接返回 null。 */
    public Set<String> readScope() {
        if (isApplying()) {
            Log.i(TAG, "readScope: framework applying, skip read to avoid half-written db");
            return null;
        }
        if (!stageFromDevice(LIVE_DB)) { Log.e(TAG, "readScope: stageFromDevice failed"); return null; }
        Set<String> scope = new LinkedHashSet<>();
        SQLiteDatabase sql = null;
        try {
            runRoot("rm -f '" + SWAL + "' '" + SSHM + "' 2>/dev/null");
            sql = SQLiteDatabase.openDatabase(SDB, null,
                    SQLiteDatabase.OPEN_READWRITE | DISABLE_WAL);
            int schema = detectSchema(sql);
            Log.i(TAG, "readScope: schema=" + schema);
            if (schema == 2) {
                Long mid = getModuleMidV2(sql);
                if (mid == null) return scope;
                try (Cursor e = sql.rawQuery("SELECT enabled FROM modules WHERE mid=?",
                        new String[]{String.valueOf(mid)})) {
                    if (e.moveToFirst() && e.getInt(0) == 0) return scope;
                }
                try (Cursor c = sql.rawQuery(
                        "SELECT app_pkg_name FROM scope WHERE mid=? AND user_id=?",
                        new String[]{String.valueOf(mid), String.valueOf(USER_ID)})) {
                    while (c.moveToNext()) scope.add(c.getString(0));
                }
            } else if (schema == 1) {
                try (Cursor e = sql.rawQuery(
                        "SELECT enabled FROM modules_state WHERE module_pkg_name=? AND user_id=?",
                        new String[]{MODULE_PKG, String.valueOf(USER_ID)})) {
                    if (!e.moveToFirst() || e.getInt(0) == 0) return scope;
                }
                try (Cursor c = sql.rawQuery(
                        "SELECT app_pkg_name FROM scope WHERE module_pkg_name=? AND user_id=?",
                        new String[]{MODULE_PKG, String.valueOf(USER_ID)})) {
                    while (c.moveToNext()) scope.add(c.getString(0));
                }
            } else {
                Log.e(TAG, "readScope: unknown schema");
                return null;
            }
            Log.i(TAG, "readScope: found " + scope.size() + " scope entries");
        } catch (Throwable t) {
            Log.e(TAG, "readScope failed", t);
            return null;
        } finally {
            if (sql != null) try { sql.close(); } catch (Throwable ignored) {}
            cleanupStage();
        }
        return scope;
    }

    private Long getModuleMidV2(SQLiteDatabase sql) {
        try (Cursor c = sql.rawQuery(
                "SELECT mid FROM modules WHERE module_pkg_name=?",
                new String[]{MODULE_PKG})) {
            if (c.moveToFirst()) return c.getLong(0);
        }
        return null;
    }

    // ============================== 写入作用域（全量同步） ==============================

    /**
     * 把本模块作用域全量同步为 {@code targetPkgs}（系统框架 system 始终保留）。
     * 自动按分支选择方案：Vector 走 cli（无重启）；官方走 4.6.1 进程内 daemon 重启（管理器仍需
     * 手机重启才能打开，仅用于用户选择「稍后」时）。
     */
    public boolean syncScope(Set<String> targetPkgs) {
        return syncScope(targetPkgs, false);
    }

    /**
     * @param softReboot 官方分支专用：true=直写库后触发框架软重启（zygote 重启，推荐，
     *                   管理器可恢复）；false=只重启 daemon（4.6.1 旧路径，需手机重启）。
     *                   Vector 分支忽略此参数。
     */
    public boolean syncScope(Set<String> targetPkgs, boolean softReboot) {
        synchronized (STATE_LOCK) {
            if (state == STATE_APPLYING) {
                Log.w(TAG, "syncScope: another framework operation in progress, reject");
                return false;
            }
            state = STATE_APPLYING;
        }
        try {
            FrameworkDetector.Info fw = FrameworkDetector.detect();
            if (fw.flavor == FrameworkDetector.FLAVOR_NONE) {
                Log.e(TAG, "syncScope: framework not detected");
                return false;
            }

            // ---- Vector：cli 直写（守护进程内部处理，零重启）----
            if (fw.cliWritesUsable()) {
                boolean ok = applyViaCli(fw.cli, targetPkgs);
                Log.i(TAG, "syncScope via cli (Vector): " + ok
                        + ", targets=" + (targetPkgs == null ? 0 : targetPkgs.size()));
                return ok;
            }

            // ---- 官方：优先经 adbd 本地执行 lspctl（免重启）----
            // lspctl 写作用域要求调用进程祖先链含 adbd。本地起 adbd TCP 端口，
            // 用内置 ADB 协议客户端（公钥已 root 写入 adb_keys，免授权弹窗）
            // 经 adbd 执行 lspctl scope set，即时生效。
            Set<String> fullScope = new LinkedHashSet<>();
            fullScope.add("system"); // 系统框架只写 system（不写 android）
            fullScope.add(MODULE_PKG); // 模块自身：使模块 UI 进程被框架注入，
                                       // libxposed service 可连接（免重启兜底通道）
            if (targetPkgs != null) fullScope.addAll(targetPkgs);

            boolean adbdOk = applyViaAdbd(fw, fullScope);
            if (adbdOk) {
                Log.i(TAG, "syncScope via adbd+lspctl (no reboot), scope=" + fullScope.size());
                return true;
            }
            Log.w(TAG, "syncScope: adbd path failed, fallback to soft reboot");

            // ---- 官方兜底：直写库 + 框架软重启 ----
            Log.i(TAG, "syncScope via direct-write+soft reboot (official LSPosed)");

            if (!stageFromDevice(LIVE_DB)) return false;

            boolean edited;
            SQLiteDatabase sql = null;
            try {
                sql = SQLiteDatabase.openDatabase(SDB, null,
                        SQLiteDatabase.OPEN_READWRITE | DISABLE_WAL);
                int schema = detectSchema(sql);
                Log.i(TAG, "syncScope: schema=" + schema + ", fullScope=" + fullScope.size());
                sql.beginTransaction();
                try {
                    String apk = context.getPackageManager()
                            .getApplicationInfo(MODULE_PKG, 0).sourceDir;
                    if (schema == 2) {
                        Long mid = getModuleMidV2(sql);
                        if (mid == null) {
                            sql.execSQL(
                                    "INSERT INTO modules (module_pkg_name, apk_path, enabled, auto_include) VALUES (?,?,1,0)",
                                    new Object[]{MODULE_PKG, apk});
                            mid = getModuleMidV2(sql);
                        } else {
                            sql.execSQL("UPDATE modules SET enabled=1 WHERE mid=?", new Object[]{mid});
                        }
                        if (mid == null) throw new IllegalStateException("no mid after insert");
                        sql.execSQL("DELETE FROM scope WHERE mid=? AND user_id=?",
                                new Object[]{mid, USER_ID});
                        for (String pkg : fullScope) {
                            if (pkg == null || pkg.isEmpty()) continue;
                            sql.execSQL("INSERT INTO scope (mid, app_pkg_name, user_id) VALUES (?,?,?)",
                                    new Object[]{mid, pkg, USER_ID});
                        }
                    } else if (schema == 1) {
                        sql.execSQL("INSERT OR IGNORE INTO modules (module_pkg_name, apk_path) VALUES (?,?)",
                                new Object[]{MODULE_PKG, apk});
                        sql.execSQL(
                                "INSERT OR IGNORE INTO modules_state (module_pkg_name, user_id, enabled, scope_request_blocked) VALUES (?,?,1,0)",
                                new Object[]{MODULE_PKG, USER_ID});
                        sql.execSQL(
                                "UPDATE modules_state SET enabled=1 WHERE module_pkg_name=? AND user_id=?",
                                new Object[]{MODULE_PKG, USER_ID});
                        sql.execSQL("DELETE FROM scope WHERE module_pkg_name=? AND user_id=?",
                                new Object[]{MODULE_PKG, USER_ID});
                        for (String pkg : fullScope) {
                            if (pkg == null || pkg.isEmpty()) continue;
                            sql.execSQL(
                                    "INSERT INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES (?,?,?)",
                                    new Object[]{MODULE_PKG, pkg, USER_ID});
                        }
                    } else {
                        throw new IllegalStateException("unknown schema");
                    }
                    sql.setTransactionSuccessful();
                    edited = true;
                } finally {
                    sql.endTransaction();
                }
            } catch (Throwable t) {
                Log.e(TAG, "syncScope edit", t);
                edited = false;
            } finally {
                if (sql != null) try { sql.close(); } catch (Throwable ignored) {}
            }

            if (!edited) {
                cleanupStage();
                Log.e(TAG, "syncScope: edit failed, abort");
                return false;
            }

            boolean restarted;
            if (softReboot) {
                // 4.6.2：直写库 + 框架软重启（zygote 重建）。暂存库保留供脱离 App 的独立脚本读取。
                restarted = launchSoftRebootScript(fw);
                if (restarted) {
                    lastRestartMs = System.currentTimeMillis();
                    Log.i(TAG, "syncScope: soft reboot script launched");
                    return true;
                }
                // 脚本启动失败：清理暂存，走失败排队
                cleanupStage();
            } else {
                restarted = stopWriteBackAndRestart(fw);
                if (restarted) {
                    lastRestartMs = System.currentTimeMillis();
                }
                cleanupStage();
            }
            Log.i(TAG, "syncScope: edited=true restarted=" + restarted + " softReboot=" + softReboot);
            return restarted;
        } finally {
            // 重启脚本在 runRoot 内同步执行到就绪确认（或回滚完成）才返回，
            // 此时守护进程与配置库都已稳定，直接释放锁即可；
            // 重启后的冷却由 lastRestartMs 保护 ensureDaemon 不重复拉起。
            setStateIdle();
        }
    }

    /**
     * 经 cli 写入作用域（root）。{@code --json} 输出 CliResponse，以 success 字段判定。
     * 无目标时禁用模块；有目标时 scope set（内部 enableModule + 全量覆盖 scope + 刷缓存）。
     */
    private boolean applyViaCli(String cli, Set<String> targets) {
        int n = targets == null ? 0 : targets.size();
        String script;
        if (n == 0) {
            script = "'" + cli + "' --json modules disable '" + MODULE_PKG + "' 2>&1";
        } else {
            StringBuilder args = new StringBuilder();
            for (String p : targets) {
                if (p == null || p.isEmpty()) continue;
                args.append(" '").append(p).append("/0'");
            }
            script = "'" + cli + "' --json scope set '" + MODULE_PKG + "'" + args + " 2>&1";
        }
        Result r = runRoot(script);
        Log.i(TAG, "applyViaCli: exit=" + r.exitCode + " output=" + r.output);
        return r.ok() && r.output.replaceAll("\\s+", "").contains("\"success\":true");
    }

    /**
     * 经 adbd 本地执行 lspctl（免重启）。
     *
     * <p>步骤：① 生成 RSA keypair（进程内缓存）；② root 自动开启 USB 调试
     * （settings put global adb_enabled 1，免手动开开发者选项）并把公钥写入
     * /data/misc/adb/adb_keys（免授权弹窗）；③ root 设置 adbd TCP 端口 5555
     * 并重启 adbd；④ {@link AdbClient} 连接，经 adbd 执行 {@code lspctl scope set}；
     * ⑤ 检查输出无报错。</p>
     */
    private static java.security.KeyPair sAdbKey = null;

    private boolean applyViaAdbd(FrameworkDetector.Info fw, Set<String> scope) {
        try {
            // 1) 生成 RSA keypair（缓存）
            if (sAdbKey == null) {
                java.security.KeyPairGenerator kpg =
                        java.security.KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                sAdbKey = kpg.generateKeyPair();
            }
            String pubLine = AdbClient.encodePublicKey(sAdbKey);

            // 复制 b64 诊断程序（用设备 bionic 的 b64_pton 实测，与 adbd 同源）
            File testBin = new File(context.getFilesDir(), "test_b64_arm64");
            try (java.io.InputStream is = context.getAssets().open("test_b64_arm64");
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(testBin)) {
                byte[] buf = new byte[8192]; int r;
                while ((r = is.read(buf)) > 0) fos.write(buf, 0, r);
            }
            String b64Only = pubLine.substring(0, pubLine.indexOf(' '));

            // 2) 自动开启 USB 调试（root 直写全局设置，免手动）+ 写公钥 + 重启 adbd
            //    先清掉历史上写入的旧格式 key（X.509 SPKI 会导致 adbd "Invalid base64 key"），
            //    只保留其他来源（如电脑授权）的 key，再写本次 PKCS#1 公钥。
            String setup =
                    "settings put global adb_enabled 1\n" +
                    "setprop persist.adb.enabled 1\n" +
                    "setprop service.adb.tcp.port 5555\n" +
                    "mkdir -p /data/misc/adb\n" +
                    "touch /data/misc/adb/adb_keys\n" +
                    "grep -v ' DeviceResetSpoofer$' /data/misc/adb/adb_keys > /data/misc/adb/adb_keys.clean 2>/dev/null; " +
                    "mv /data/misc/adb/adb_keys.clean /data/misc/adb/adb_keys\n" +
                    "grep -qF '" + pubLine + "' /data/misc/adb/adb_keys 2>/dev/null || "
                            + "echo '" + pubLine + "' >> /data/misc/adb/adb_keys\n" +
                    "chown system:shell /data/misc/adb /data/misc/adb/adb_keys 2>/dev/null\n" +
                    "chmod 775 /data/misc/adb 2>/dev/null; chmod 660 /data/misc/adb/adb_keys 2>/dev/null\n" +
                    "echo === KEYS DIAG START ===\n" +
                    "ls -la /apex/com.android.adbd/bin/adbd /system/bin/adbd 2>/dev/null\n" +
                    "wc -l /data/misc/adb/adb_keys\n" +
                    "ls -laZ /data/misc/adb/adb_keys\n" +
                    "sed 's/ .*//' /data/misc/adb/adb_keys | while read k; do echo \"len=${#k} decode=$(echo -n \"$k\" | base64 -d 2>/dev/null | wc -c)\"; done\n" +
                    "hexdump -C /data/misc/adb/adb_keys | head -2\n" +
                    "hexdump -C /data/misc/adb/adb_keys | tail -2\n" +
                    "echo === B64 NATIVE TEST ===\n" +
                    "cp '" + testBin.getAbsolutePath() + "' /data/local/tmp/test_b64 2>/dev/null\n" +
                    "chmod 755 /data/local/tmp/test_b64\n" +
                    "/data/local/tmp/test_b64 '" + b64Only + "'\n" +
                    "echo === ADB BIN ===\n" +
                    "ADBBIN=$(ls /apex/com.android.adbd/bin/adbd 2>/dev/null || echo /system/bin/adbd)\n" +
                    "echo adbd=$ADBBIN\n" +
                    "strings \"$ADBBIN\" | grep -iE \"adb_keys|pubkey|/data/misc/adb\" | head -10\n" +
                    "echo === KEYS DIAG END ===\n" +
                    "stop adbd 2>/dev/null; sleep 1; start adbd\n" +
                    "echo ADB_SETUP_DONE\n";
            Result sr = runRoot(setup);
            Log.i(TAG, "applyViaAdbd setup: " + sr.output);
            if (!sr.output.contains("ADB_SETUP_DONE")) return false;

            // 等待 adbd 监听端口（重启后稍等）
            Thread.sleep(2500);

            // 4) 构造 lspctl 命令（scope set 全量覆盖）
            StringBuilder cmd = new StringBuilder();
            cmd.append(fw.moduleDir).append("lspctl scope set '").append(MODULE_PKG).append("'");
            for (String p : scope) {
                if (p == null || p.isEmpty()) continue;
                cmd.append(" '").append(p).append("/0'");
            }
            cmd.append(" 2>&1");

            // 5) 经 adbd 执行
            AdbClient client = new AdbClient("127.0.0.1", 5555, sAdbKey);
            try {
                client.connect();
                String output = client.shell(cmd.toString());
                Log.i(TAG, "applyViaAdbd lspctl output: " + output);
                // lspctl 成功时无错误输出；失败包含 error/denied/拒绝
                String low = output.toLowerCase();
                return !low.contains("error") && !low.contains("denied")
                        && !low.contains("refused") && !low.contains("unauthorized");
            } finally {
                client.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyViaAdbd failed", t);
            return false;
        }
    }

    /**
     * 4.6.2 框架软重启脚本（以 root、setsid 独立会话执行，不依赖本 App 存活——
     * zygote 重启会杀死所有 App，包括本应用）。
     *
     * 参数：$1=模块目录 $2=活库 $3=暂存新库 $4=旧库备份 $5=marker
     * 关键顺序：停 daemon → 写库 → 先起新 daemon（lspbridge 套接字由 daemon 创建，
     * 新 zygote 注入侧会以 50ms 间隔反复重连）→ ctl.restart zygote（主 zygote+system_server 重建）
     * → 属性被拒则 SIGKILL 兜底（init 自动重启）→ 轮询就绪，写 RESTART_OK/FAIL。
     */
    private static final String APPLY_SCRIPT =
            "#!/system/bin/sh\n" +
            "set +e\n" +
            "MODDIR=\"$1\"; LIVE=\"$2\"; STAGED=\"$3\"; BACKUP=\"$4\"; MARKER=\"$5\"\n" +
            "LSPCTL=\"$MODDIR/lspctl\"\n" +
            "log(){ echo \"$(date '+%m-%d %H:%M:%S') $*\" >> \"$MARKER\"; }\n" +
            "log START\n" +
            // 1) 优雅停机
            "\"$LSPCTL\" stop >/dev/null 2>&1\n" +
            "i=0\n" +
            "while pidof lspd >/dev/null 2>&1; do\n" +
            "  i=$((i+1)); if [ $i -ge 40 ]; then break; fi; sleep 0.2\n" +
            "done\n" +
            "if pidof lspd >/dev/null 2>&1; then\n" +
            "  for p in $(pidof lspd); do kill -TERM \"$p\" 2>/dev/null; done\n" +
            "  i=0\n" +
            "  while pidof lspd >/dev/null 2>&1; do\n" +
            "    i=$((i+1))\n" +
            "    if [ $i -ge 25 ]; then\n" +
            "      for p in $(pidof lspd); do kill -KILL \"$p\" 2>/dev/null; done\n" +
            "      sleep 1; break\n" +
            "    fi; sleep 0.2\n" +
            "  done\n" +
            "fi\n" +
            "sleep 0.5\n" +
            // 2) 写库（daemon 已停，无 WAL 竞争）
            "cp \"$STAGED\" \"$LIVE\"\n" +
            "rm -f \"$LIVE-wal\" \"$LIVE-shm\"\n" +
            "chmod 660 \"$LIVE\" 2>/dev/null\n" +
            "chown root:root \"$LIVE\" 2>/dev/null\n" +
            "restorecon -F \"$LIVE\" 2>/dev/null\n" +
            "sync\n" +
            "log DB_WRITTEN\n" +
            // 3) 先拉起新 daemon（必须在 zygote 重启前：套接字由 daemon 创建）
            "cd \"$MODDIR\" || { log FAIL_NO_MODDIR; exit 1; }\n" +
            "setsid sh ./daemon --force </dev/null >/dev/null 2>&1 &\n" +
            "sleep 4\n" +
            // 4) 框架软重启：主 zygote（承载 system_server）+ 32 位副 zygote
            "if [ -z \"$(pidof zygote64 2>/dev/null)\" ]; then ZNAME=zygote; else ZNAME=zygote64; fi\n" +
            "ZPID=\"$(pidof \"$ZNAME\" 2>/dev/null)\"\n" +
            "setprop ctl.restart zygote 2>/dev/null\n" +
            "sleep 2\n" +
            "setprop ctl.restart zygote_secondary 2>/dev/null\n" +
            "log CTRL_RESTART_SENT\n" +
            // 5) 等待 20 秒，主 zygote pid 未变说明属性被拒，SIGKILL 兜底
            "i=0\n" +
            "while [ $i -lt 20 ]; do\n" +
            "  i=$((i+1)); sleep 1\n" +
            "  NZPID=\"$(pidof \"$ZNAME\" 2>/dev/null)\"\n" +
            "  if [ -n \"$NZPID\" ] && [ \"$NZPID\" != \"$ZPID\" ]; then log ZYGOTE_RESTARTED; break; fi\n" +
            "done\n" +
            "NZPID=\"$(pidof \"$ZNAME\" 2>/dev/null)\"\n" +
            "if [ \"$NZPID\" = \"$ZPID\" ]; then\n" +
            "  log CTRL_RESTART_NO_EFFECT_KILL_FALLBACK\n" +
            "  killall -9 \"$ZNAME\" 2>/dev/null\n" +
            "  sleep 2\n" +
            "  killall -9 zygote_secondary 2>/dev/null\n" +
            "fi\n" +
            // 6) 就绪轮询：软重启约 30-90s，连续 3 次 status 成功（最长 180s）
            "i=0; streak=0\n" +
            "while [ $i -lt 180 ]; do\n" +
            "  i=$((i+1)); sleep 1\n" +
            "  if pidof lspd >/dev/null 2>&1 && \"$LSPCTL\" status >/dev/null 2>&1; then\n" +
            "    streak=$((streak+1))\n" +
            "    if [ $streak -ge 3 ]; then log RESTART_OK; exit 0; fi\n" +
            "  else streak=0; fi\n" +
            "done\n" +
            "log RESTART_FAIL\n" +
            "exit 1\n";

    /**
     * 把 APPLY_SCRIPT 写盘并以 setsid 独立会话启动（软重启模式）。
     * 启动后轮询 marker 直到看到 START，确认脚本已脱离 App 开始执行。
     * 暂存库在 App 被杀死后仍留在 filesDir，由 root 脚本继续读取。
     */
    private boolean launchSoftRebootScript(FrameworkDetector.Info fw) {
        // 写脚本（heredoc 引号包裹，禁止任何变量展开）
        String writeScript =
                "cat > '" + APPLY_SCRIPT_PATH + "' <<'DRS_EOF'\n" + APPLY_SCRIPT + "DRS_EOF\n" +
                "chmod 755 '" + APPLY_SCRIPT_PATH + "'\n" +
                "rm -f '" + REBOOT_MARKER_PATH + "'\n" +
                "echo SCRIPT_READY\n";
        Result w = runRoot(writeScript);
        if (!w.output.contains("SCRIPT_READY")) {
            Log.e(TAG, "launchSoftRebootScript: write script failed: " + w.output);
            return false;
        }
        String launch =
                "setsid sh '" + APPLY_SCRIPT_PATH + "' '" + fw.moduleDir + "' '" + LIVE_DB
                        + "' '" + SDB + "' '" + BACKUP_DB + "' '" + REBOOT_MARKER_PATH
                        + "' </dev/null >'" + APPLY_LOG_PATH + "' 2>&1 &\n" +
                "echo LAUNCHED\n";
        Result r = runRoot(launch);
        if (!r.output.contains("LAUNCHED")) {
            Log.e(TAG, "launchSoftRebootScript: launch failed: " + r.output);
            return false;
        }
        // 确认脚本已启动（marker 出现 START）
        for (int i = 0; i < 25; i++) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { return false; }
            Result m = runRoot("cat '" + REBOOT_MARKER_PATH + "' 2>/dev/null");
            if (m.output.contains("START")) {
                Log.i(TAG, "launchSoftRebootScript: marker START seen");
                return true;
            }
        }
        Log.e(TAG, "launchSoftRebootScript: no START marker after 5s");
        return false;
    }

    /**
     * 读取软重启 marker 最新状态：返回 null（无 marker）或最后一行内容。
     * 含 RESTART_OK / RESTART_FAIL / START / DB_WRITTEN / CTRL_RESTART_SENT / ZYGOTE_RESTARTED。
     */
    public String readRebootMarker() {
        Result r = runRoot("tail -1 '" + REBOOT_MARKER_PATH + "' 2>/dev/null");
        if (r == null || r.output == null) return null;
        String s = r.output.trim();
        return s.isEmpty() ? null : s;
    }

    /** 软重启结束（成功/失败）后清理 marker、脚本与暂存库。 */
    public void clearRebootArtifacts() {
        runRoot("rm -f '" + REBOOT_MARKER_PATH + "' '" + APPLY_SCRIPT_PATH + "' '" + APPLY_LOG_PATH + "'");
        cleanupStage();
    }

    /**
     * 官方分支完整闭环（单 root 会话内，串行锁保护）：
     * 1. 先用官方公开命令 {@code lspctl stop} 优雅停机，等待死亡；无响应再 TERM/KILL；
     * 2. 守护进程完全停止后，拷回新库（无 WAL 竞争）+ chmod/chown/restorecon + sync；
     * 3. 复刻官方 service.sh：cd 模块目录 + setsid 起 daemon --force；
     * 4. pidof + lspctl status 轮询，连续 3 次成功才算就绪（约 100 秒，覆盖首次 dex2oat）；
     * 5. 成功 RESTART_OK；失败则恢复旧库、重新拉起并等待，输出 RESTART_FAIL（不留下半激活）。
     * 注意：此路径（4.6.1）后管理器桥接仍断裂，需手机重启才能打开管理器；仅用于用户选择
     * 「稍后」时。默认应走 {@link #launchSoftRebootScript}（4.6.2 框架软重启）。
     */
    private boolean stopWriteBackAndRestart(FrameworkDetector.Info fw) {
        final String daemon = fw.moduleDir + "daemon";
        final String lspctl = fw.moduleDir + "lspctl";
        final String script =
                "set +e\n" +
                // 1) 优雅停机：官方公开命令 lspctl stop（无来源门）
                "'" + lspctl + "' stop >/dev/null 2>&1\n" +
                "i=0\n" +
                "while pidof lspd >/dev/null 2>&1; do\n" +
                "  i=$((i+1))\n" +
                "  if [ $i -ge 40 ]; then break; fi\n" +  // 8 秒优雅等待
                "  sleep 0.2\n" +
                "done\n" +
                // 2) 仍存活：TERM 5 秒，再 KILL
                "if pidof lspd >/dev/null 2>&1; then\n" +
                "  for p in $(pidof lspd); do kill -TERM \"$p\" 2>/dev/null; done\n" +
                "  i=0\n" +
                "  while pidof lspd >/dev/null 2>&1; do\n" +
                "    i=$((i+1))\n" +
                "    if [ $i -ge 25 ]; then\n" +
                "      for p in $(pidof lspd); do kill -KILL \"$p\" 2>/dev/null; done\n" +
                "      sleep 1; break\n" +
                "    fi\n" +
                "    sleep 0.2\n" +
                "  done\n" +
                "fi\n" +
                "sleep 0.5\n" +
                "echo OLD_STOPPED\n" +
                // 3) 守护进程已停止 → 拷回新库（此时无进程持有 WAL）
                "cp '" + SDB + "' '" + LIVE_DB + "'\n" +
                "rm -f '" + LIVE_DB + "-wal' '" + LIVE_DB + "-shm'\n" +
                "chmod 660 '" + LIVE_DB + "' 2>/dev/null\n" +
                "chown root:root '" + LIVE_DB + "' 2>/dev/null\n" +
                "restorecon -F '" + LIVE_DB + "' 2>/dev/null\n" +
                "sync\n" +
                "echo DB_WRITTEN\n" +
                // 4) 复刻官方 service.sh：cd 模块目录 + setsid 独立会话
                "cd '" + fw.moduleDir + "'\n" +
                "setsid sh ./daemon --force </dev/null >/dev/null 2>&1 &\n" +
                "echo NEW_LAUNCHED\n" +
                // 5) 就绪轮询：pidof 存活且连续 3 次 lspctl status 成功
                "i=0\n" +
                "ok=0\n" +
                "streak=0\n" +
                "while [ $i -lt 100 ]; do\n" +
                "  i=$((i+1))\n" +
                "  sleep 1\n" +
                "  if pidof lspd >/dev/null 2>&1 && '" + lspctl + "' status >/dev/null 2>&1; then\n" +
                "    streak=$((streak+1))\n" +
                "    if [ $streak -ge 3 ]; then ok=1; break; fi\n" +
                "  else\n" +
                "    streak=0\n" +
                "  fi\n" +
                "done\n" +
                "if [ $ok -eq 1 ]; then echo RESTART_OK; exit 0; fi\n" +
                // 6) 失败：KILL 新进程 → 恢复旧库 → 原样拉起并等待
                "echo RESTART_FAIL, restoring previous db\n" +
                "for p in $(pidof lspd); do kill -KILL \"$p\" 2>/dev/null; done\n" +
                "sleep 1\n" +
                "cp '" + BACKUP_DB + "' '" + LIVE_DB + "'\n" +
                "chmod 660 '" + LIVE_DB + "' 2>/dev/null\n" +
                "chown root:root '" + LIVE_DB + "' 2>/dev/null\n" +
                "restorecon -F '" + LIVE_DB + "' 2>/dev/null\n" +
                "sync\n" +
                "cd '" + fw.moduleDir + "'\n" +
                "setsid sh ./daemon --force </dev/null >/dev/null 2>&1 &\n" +
                "i=0\n" +
                "streak=0\n" +
                "while [ $i -lt 100 ]; do\n" +
                "  i=$((i+1))\n" +
                "  sleep 1\n" +
                "  if pidof lspd >/dev/null 2>&1 && '" + lspctl + "' status >/dev/null 2>&1; then\n" +
                "    streak=$((streak+1))\n" +
                "    if [ $streak -ge 3 ]; then break; fi\n" +
                "  else streak=0; fi\n" +
                "done\n" +
                "echo ROLLBACK_DONE\n";
        Result r = runRoot(script);
        Log.i(TAG, "stopWriteBackAndRestart: exit=" + r.exitCode + "\n" + r.output);
        return r.output.contains("RESTART_OK");
    }

    private void cleanupStage() {
        runRoot("rm -rf '" + stageDir.getAbsolutePath() + "'");
    }

    // ============================== 守护进程 / 目标应用 ==============================

    public boolean isDaemonAlive() {
        Result r = runRoot("pidof lspd >/dev/null 2>&1 && echo ALIVE || echo DEAD");
        return r.output.contains("ALIVE");
    }

    /**
     * 确保守护进程存活（App 启动时调用）。
     * 串行锁保护：作用域写入的重启窗口 / 重启后冷却期内一律不拉起，避免多实例争抢 binder。
     * 已存活什么都不做；确认已死且不在冷却期才安全拉起并轮询确认。
     */
    public boolean ensureDaemon() {
        synchronized (STATE_LOCK) {
            if (state == STATE_APPLYING) {
                Log.i(TAG, "ensureDaemon: applying in progress, skip");
                return true;
            }
            if (System.currentTimeMillis() - lastRestartMs < POST_RESTART_COOLDOWN_MS) {
                Log.i(TAG, "ensureDaemon: post-restart cooldown, skip");
                return true;
            }
            state = STATE_APPLYING;
        }
        try {
            if (isDaemonAlive()) { Log.i(TAG, "ensureDaemon: already alive"); return true; }
            Log.w(TAG, "ensureDaemon: daemon dead, starting...");
            FrameworkDetector.Info fw = FrameworkDetector.detect();
            if (fw.flavor == FrameworkDetector.FLAVOR_NONE) {
                Log.e(TAG, "ensureDaemon: framework not detected"); return false;
            }
            String daemon = fw.moduleDir + "daemon";
            String lspctl = fw.moduleDir + "lspctl";
            String script =
                    "set +e\n" +
                    "cd '" + fw.moduleDir + "'\n" +
                    "setsid sh ./daemon --force </dev/null >/dev/null 2>&1 &\n" +
                    "i=0\n" +
                    "streak=0\n" +
                    "while [ $i -lt 100 ]; do\n" +
                    "  i=$((i+1))\n" +
                    "  sleep 1\n" +
                    "  if pidof lspd >/dev/null 2>&1 && '" + lspctl + "' status >/dev/null 2>&1; then\n" +
                    "    streak=$((streak+1))\n" +
                    "    if [ $streak -ge 3 ]; then break; fi\n" +
                    "  else streak=0; fi\n" +
                    "done\n" +
                    "if pidof lspd >/dev/null 2>&1 && '" + lspctl + "' status >/dev/null 2>&1; then\n" +
                    "  echo DAEMON_UP\n" +
                    "else echo DAEMON_DOWN\nfi";
            Result r = runRoot(script);
            boolean up = r.output.contains("DAEMON_UP");
            Log.i(TAG, "ensureDaemon: " + (up ? "started" : "FAILED") + " output=" + r.output);
            return up;
        } finally {
            setStateIdle();
        }
    }

    /** 强制停止目标应用（root），下次打开即为注入后的全新进程。 */
    public boolean forceStop(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Result r = runRoot("am force-stop '" + pkg + "'; echo FS_DONE");
        return r.output.contains("FS_DONE");
    }
}
