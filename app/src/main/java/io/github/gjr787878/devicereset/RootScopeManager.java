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
 * Root 作用域直写管理器（零点击生效）。
 *
 * <p>在已授予 root 的设备上，无需打开 LSPosed/Vector 管理器、也无需在通知上点“批准”，
 * 直接把目标应用写入框架的作用域数据库 {@code modules_config.db}，随后<b>重启 lspd 守护进程</b>
 * 使其丢弃旧的内存 ConfigCache、从数据库重建作用域，再强制停止目标应用 ——
 * 实现“勾上即生效、零点击”。</p>
 *
 * <p><b>为什么必须重启守护进程（已用设备数据证实）</b>：</p>
 * <ul>
 *   <li>守护进程（反编译 j7 / b9）把作用域缓存在内存（{@code j7.s} ConfigCache）。
 *       zygote fork 普通应用时，注入侧通过 socket 字节 4 向守护进程查询模块列表，
 *       结果直接来自<b>内存缓存</b>而非数据库。</li>
 *   <li>守护进程内<b>不存在 FileObserver</b>（全量反编译检索证实），直接改库不会触发
 *       内存缓存重建 —— 这是此前“库已写对、但应用仍未注入、没有任何改进”的根因。</li>
 *   <li>框架自身的实时生效路径（CLI 字节 5 → j7.E：写库 + I(false) 刷缓存 + u8.p 杀进程）
 *       被两道门挡住：开发者模式（需管理器设置）+ CliOriginVerifier（z1.b，要求调用进程
 *       祖先链含 adbd、且有 ADB tty）。App 经 su 无人值守调用无法通过，故只能走
 *       “直写 + 重启守护进程”。</li>
 * </ul>
 *
 * <p><b>如何避免“框架未完全激活/部分激活”</b>：</p>
 * <ul>
 *   <li>旧实现的问题：killall lspd 后经 service.sh 拉起的新进程<b>未正确脱离 su 会话</b>，
 *       su 会话结束即被带走 → 守护进程长时间缺失 → 管理器持续报“部分激活”。</li>
 *   <li>本实现用 {@code setsid} 把新守护进程放进独立会话（stdio 全重定向到 /dev/null），
 *       不随 su 会话死亡；启动后用框架自带 {@code lspctl status} 轮询直到守护进程真正就绪，
 *       再返回成功。守护进程启动时会通过 j1.a 主动向 system_server 注入侧（activity 服务）
 *       重新注册，system_server 链路随重启自动恢复。</li>
 * </ul>
 *
 * <p><b>其他要点</b>：</p>
 * <ul>
 *   <li>暂存目录放在模块私有 filesDir（root 拷出后 chown 回本应用 uid、restorecon）；
 *       不依赖设备 sqlite3：root 拷出 → 应用内 SQLite 改 → root 拷回。</li>
 *   <li>打开暂存库时显式 DISABLE_WRITE_AHEAD_LOGGING，避免产生 -wal 导致 cp 主库丢数据。</li>
 *   <li>兼容两种 schema：v101 旧版（modules/modules_state/scope 文本包名列）与
 *       Vector 新版（modules.mid / scope.mid）。</li>
 * </ul>
 */
public final class RootScopeManager {

    private static final String TAG = "RootScopeManager";
    private static final String MODULE_PKG = "io.github.gjr787878.devicereset";
    private static final int USER_ID = 0;

    /**
     * 等价于 android.database.sqlite.DISABLE_WAL（@hide 常量，
     * 公开 SDK 不可见），数值 0x02000000：打开数据库时强制关闭 WAL（避免产生 -wal 导致拷回丢数据）。
     */
    private static final int DISABLE_WAL = 0x02000000;

    private final Context context;
    private final int appUid;
    private final File stageDir;
    private final File stageDb;
    private final String SDB;   // 暂存库绝对路径
    private final String SWAL;
    private final String SSHM;

    private Boolean rootCached = null;
    private String dbPathCached = null;
    private String moduleDirCached = null;

    public RootScopeManager(Context context) {
        this.context = context.getApplicationContext();
        this.appUid = android.os.Process.myUid();
        this.stageDir = new File(this.context.getFilesDir(), "drs_root");
        this.stageDb = new File(stageDir, "modules_config.db");
        this.SDB = stageDb.getAbsolutePath();
        this.SWAL = SDB + "-wal";
        this.SSHM = SDB + "-shm";
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

    // ============================== 路径探测 ==============================

    /** 定位 modules_config.db（结果缓存）。 */
    public String findDbPath() {
        if (dbPathCached != null) return dbPathCached;
        String script =
                "for p in \\\n" +
                "  /data/adb/lspd/config/modules_config.db \\\n" +
                "  /data/adb/modules/zygisk_lsposed/config/modules_config.db \\\n" +
                "  /data/adb/modules/zygisk_vector/config/modules_config.db \\\n" +
                "  /data/adb/modules/*lsposed*/config/modules_config.db \\\n" +
                "  /data/adb/modules/*lspd*/config/modules_config.db \\\n" +
                "  /data/adb/modules/*vector*/config/modules_config.db; do\n" +
                "  [ -f \"$p\" ] && echo \"$p\" && break\n" +
                "done";
        Result r = runRoot(script);
        Log.i(TAG, "findDbPath: exit=" + r.exitCode + " output=" + r.output);
        if (r.ok() && !r.output.isEmpty()) {
            String p = r.output.split("\\n")[0].trim();
            if (!p.isEmpty() && p.contains("modules_config.db")) {
                dbPathCached = p;
                return p;
            }
        }
        return null;
    }

    /** 定位框架 magisk 模块目录（含 daemon、lspctl 与 service.sh），用于重启守护进程。 */
    public String findModuleDir() {
        if (moduleDirCached != null) return moduleDirCached;
        String script =
                "for d in /data/adb/modules/*/ /data/adb/modules_update/*/; do\n" +
                "  if [ -f \"$d/daemon\" ] && [ -f \"$d/lspctl\" ]; then\n" +
                "    case \"$d\" in *lsposed*|*lspd*|*vector*) echo \"$d\"; break;; esac\n" +
                "  fi\n" +
                "done\n" +
                "if [ -z \"$moduleDir\" ]; then\n" +
                "  for d in /data/adb/modules/*/; do\n" +
                "    [ -f \"$d/daemon\" ] && [ -f \"$d/lspctl\" ] && { echo \"$d\"; break; }\n" +
                "  done\n" +
                "fi";
        Result r = runRoot(script);
        if (r.ok() && !r.output.isEmpty()) {
            String d = r.output.split("\\n")[0].trim();
            if (d.endsWith("/")) { moduleDirCached = d; return d; }
        }
        return null;
    }

    // ============================== 拷出（root → filesDir） ==============================

    /** root 把 db(+wal/shm) 拷到私有暂存目录，并 chown/restorecon 让本应用可读写。 */
    private boolean stageFromDevice(String db) {
        String script =
                "rm -rf '" + stageDir.getAbsolutePath() + "'\n" +
                "mkdir -p '" + stageDir.getAbsolutePath() + "'\n" +
                "cp '" + db + "' '" + SDB + "' 2>&1\n" +
                "[ -f '" + db + "-wal' ] && cp '" + db + "-wal' '" + SWAL + "' 2>&1\n" +
                "[ -f '" + db + "-shm' ] && cp '" + db + "-shm' '" + SSHM + "' 2>&1\n" +
                "chmod 666 '" + SDB + "' 2>/dev/null\n" +
                "[ -f '" + SWAL + "' ] && chmod 666 '" + SWAL + "' 2>/dev/null\n" +
                "chown -R " + appUid + ":" + appUid + " '" + stageDir.getAbsolutePath() + "' 2>&1\n" +
                "restorecon -RF '" + stageDir.getAbsolutePath() + "' 2>&1\n" +
                "ls -la '" + stageDir.getAbsolutePath() + "'\n" +
                "echo STAGED";
        Result r = runRoot(script);
        Log.i(TAG, "stageFromDevice: exit=" + r.exitCode + " output=" + r.output);
        boolean ok = r.output.contains("STAGED") && stageDb.exists() && stageDb.canRead();
        Log.i(TAG, "stageFromDevice: ok=" + ok + " exists=" + stageDb.exists() + " canRead=" + stageDb.canRead());
        return ok;
    }

    // ============================== 表结构检测 ==============================

    /**
     * 检测 LSPosed 数据库表结构版本。
     * 返回：
     *   2 = 新版 Vector/LSPosed: modules(mid, ...) + scope(mid, app_pkg_name, user_id)
     *   1 = 旧版 v101: modules(module_pkg_name, apk_path) + modules_state + scope(module_pkg_name, ...)
     *   0 = 未知/无 scope 表
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
            // 检测 modules 表是否有 mid 列
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

    /** 读取本模块当前作用域包名集合。失败返回 null。 */
    public Set<String> readScope() {
        String db = findDbPath();
        Log.i(TAG, "readScope: dbPath=" + db);
        if (db == null) { Log.e(TAG, "readScope: db not found"); return null; }
        if (!stageFromDevice(db)) { Log.e(TAG, "readScope: stageFromDevice failed"); return null; }
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
                Log.i(TAG, "readScope: mid=" + mid);
                if (mid == null) return scope; // 模块未启用，返回空集合
                try (Cursor c = sql.rawQuery(
                        "SELECT app_pkg_name FROM scope WHERE mid=? AND user_id=?",
                        new String[]{String.valueOf(mid), String.valueOf(USER_ID)})) {
                    while (c.moveToNext()) scope.add(c.getString(0));
                }
            } else if (schema == 1) {
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
     * 把本模块作用域全量同步为 {@code targetPkgs}，并确保模块已启用；
     * 随后<b>重启守护进程</b>（这是让内存缓存重建、改动真正生效的必要步骤）。
     * 兼容新版(mid schema)和旧版(module_pkg_name schema)两种表结构。
     * 内部自动确保 system 和 android 始终在作用域中（系统框架注入的前提）。
     * 返回 true 仅当：库已改对 + 守护进程已重启并经 lspctl 验证就绪。
     */
    public boolean syncScope(Set<String> targetPkgs) {
        String db = findDbPath();
        if (db == null) { Log.e(TAG, "syncScope: db not found"); return false; }
        String modDir = findModuleDir();
        if (modDir == null) { Log.e(TAG, "syncScope: module dir not found"); return false; }

        // 确保 system 和 android 始终在作用域中（否则系统框架注入失败）
        Set<String> fullScope = new LinkedHashSet<>();
        fullScope.add("system");
        fullScope.add("android");
        if (targetPkgs != null) fullScope.addAll(targetPkgs);
        Log.i(TAG, "syncScope: targets=" + (targetPkgs==null?0:targetPkgs.size())
                + " + system/android = " + fullScope.size());

        if (!stageFromDevice(db)) return false;

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
                    // 新版：modules(mid, ...) + scope(mid, ...)
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
                    // 旧版 v101：modules(module_pkg_name, apk_path) + modules_state + scope(module_pkg_name, ...)
                    sql.execSQL("INSERT OR IGNORE INTO modules (module_pkg_name, apk_path) VALUES (?,?)",
                            new Object[]{MODULE_PKG, apk});
                    // 确保 modules_state 存在且 enabled=1
                    sql.execSQL(
                            "INSERT OR IGNORE INTO modules_state (module_pkg_name, user_id, enabled, scope_request_blocked) VALUES (?,?,1,0)",
                            new Object[]{MODULE_PKG, USER_ID});
                    sql.execSQL(
                            "UPDATE modules_state SET enabled=1 WHERE module_pkg_name=? AND user_id=?",
                            new Object[]{MODULE_PKG, USER_ID});
                    // scope: 全量替换该模块的主用户作用域（DELETE 即“取消勾选”生效）
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

        // 重启守护进程：停旧 → 拷回新库 → setsid 起新（独立会话，不随 su 死亡）→ lspctl 轮询就绪
        boolean restarted = stopWriteBackAndRestart(modDir, db);
        cleanupStage();
        Log.i(TAG, "syncScope: edited=true restarted=" + restarted);
        return restarted;
    }

    /**
     * 完整重启闭环（全部在一个 root 会话内完成）：
     * 1. SIGTERM 停旧守护进程（超时 SIGKILL）；
     * 2. 拷回新库（chmod 660 / chown root:root / restorecon，与 live 一致）+ sync；
     * 3. setsid 起新守护进程（独立会话、stdio 到 /dev/null，不随 su 会话死亡）；
     * 4. lspctl status 轮询直到就绪；
     * 5. 最终再确认一次，输出 RESTART_OK / RESTART_FAIL。
     */
    private boolean stopWriteBackAndRestart(String modDir, String db) {
        String daemon = modDir + "daemon";
        String lspctl = modDir + "lspctl";
        String script =
                // 1) 停旧
                "set +e\n" +
                "for p in $(pidof lspd); do kill -TERM \"$p\" 2>/dev/null; done\n" +
                "i=0\n" +
                "while pidof lspd >/dev/null 2>&1; do\n" +
                "  i=$((i+1))\n" +
                "  if [ $i -ge 15 ]; then\n" +
                "    for p in $(pidof lspd); do kill -KILL \"$p\" 2>/dev/null; done\n" +
                "    sleep 0.5; break\n" +
                "  fi\n" +
                "  sleep 0.2\n" +
                "done\n" +
                "echo OLD_STOPPED\n" +
                // 2) 拷回新库
                "cp '" + SDB + "' '" + db + "'\n" +
                "rm -f '" + db + "-wal' '" + db + "-shm'\n" +
                "chmod 660 '" + db + "' 2>/dev/null\n" +
                "chown root:root '" + db + "' 2>/dev/null\n" +
                "restorecon -F '" + db + "' 2>/dev/null\n" +
                "sync\n" +
                "echo DB_WRITTEN\n" +
                // 3) setsid 起新守护进程（独立会话）
                "setsid sh '" + daemon + "' --force </dev/null >/dev/null 2>&1 &\n" +
                "echo NEW_LAUNCHED\n" +
                // 4) lspctl 轮询就绪（最多约 40*0.5s，加上每次调用耗时）
                "i=0\n" +
                "until '" + lspctl + "' status >/dev/null 2>&1; do\n" +
                "  i=$((i+1))\n" +
                "  if [ $i -ge 40 ]; then break; fi\n" +
                "  sleep 0.5\n" +
                "done\n" +
                // 5) 最终确认
                "if pidof lspd >/dev/null 2>&1 && '" + lspctl + "' status >/dev/null 2>&1; then\n" +
                "  echo RESTART_OK\n" +
                "else\n" +
                "  echo RESTART_FAIL\n" +
                "fi";
        Result r = runRoot(script);
        Log.i(TAG, "stopWriteBackAndRestart: exit=" + r.exitCode + "\n" + r.output);
        return r.output.contains("RESTART_OK");
    }

    private void cleanupStage() {
        runRoot("rm -rf '" + stageDir.getAbsolutePath() + "'");
    }

    // ============================== 守护进程 / 目标应用 ==============================

    /** 检测 lspd 守护进程是否存活。 */
    public boolean isDaemonAlive() {
        Result r = runRoot("pidof lspd >/dev/null 2>&1 && echo ALIVE || echo DEAD");
        return r.output.contains("ALIVE");
    }

    /**
     * 确保守护进程存活（在 App 启动时调用）：已存活则什么都不做；
     * 已死则用 setsid 安全拉起（独立会话，不随 su 会话死亡），并 lspctl 轮询确认就绪。
     */
    public boolean ensureDaemon() {
        if (isDaemonAlive()) {
            Log.i(TAG, "ensureDaemon: already alive");
            return true;
        }
        Log.w(TAG, "ensureDaemon: daemon dead, starting...");
        String modDir = findModuleDir();
        if (modDir == null) { Log.e(TAG, "ensureDaemon: module dir not found"); return false; }
        String daemon = modDir + "daemon";
        String lspctl = modDir + "lspctl";
        String script =
                "set +e\n" +
                "setsid sh '" + daemon + "' --force </dev/null >/dev/null 2>&1 &\n" +
                "i=0\n" +
                "until '" + lspctl + "' status >/dev/null 2>&1; do\n" +
                "  i=$((i+1))\n" +
                "  if [ $i -ge 40 ]; then break; fi\n" +
                "  sleep 0.5\n" +
                "done\n" +
                "if pidof lspd >/dev/null 2>&1 && '" + lspctl + "' status >/dev/null 2>&1; then\n" +
                "  echo DAEMON_UP\n" +
                "else\n" +
                "  echo DAEMON_DOWN\n" +
                "fi";
        Result r = runRoot(script);
        boolean up = r.output.contains("DAEMON_UP");
        Log.i(TAG, "ensureDaemon: " + (up ? "started" : "FAILED") + " output=" + r.output);
        return up;
    }

    /** 强制停止目标应用（root），下次打开即为注入后的全新进程。 */
    public boolean forceStop(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Result r = runRoot("am force-stop '" + pkg + "'; echo FS_DONE");
        return r.output.contains("FS_DONE");
    }
}
