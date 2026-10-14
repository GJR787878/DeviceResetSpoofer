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
 *       只能直写库后重启守护进程。重启严格复刻官方 {@code service.sh} 的
 *       {@code daemon --force}，就绪轮询放宽到约 90 秒（覆盖首次 dex2oat），并在失败时
 *       自动恢复旧库、重新拉起，<b>绝不把框架留在半激活状态</b>。</li>
 * </ul>
 *
 * <p>作用域中系统框架只写 {@code system}（加载时 packageName 才是 {@code android}），
 * 不再写冗余的 {@code android} 行。只操作本模块自己的行，其他模块完全不受影响。</p>
 *
 * <p>暂存目录放在模块私有 filesDir（root 拷出后 chown 回本应用 uid、restorecon）；不依赖设备
 * sqlite3。打开暂存库时显式 DISABLE_WAL，避免产生 -wal 导致拷回丢数据。</p>
 */
public final class RootScopeManager {

    private static final String TAG = "RootScopeManager";
    private static final String MODULE_PKG = "io.github.gjr787878.devicereset";
    private static final int USER_ID = 0;
    private static final String LIVE_DB = "/data/adb/lspd/config/modules_config.db";

    /** 等价于 android.database.sqlite.DISABLE_WAL（@hide 常量，数值 0x02000000）。 */
    private static final int DISABLE_WAL = 0x02000000;

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

    /** 读取本模块当前作用域包名集合。失败返回 null。 */
    public Set<String> readScope() {
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
     * 自动按分支选择方案：Vector 走 cli（无重启）；官方走直写 + 重启（失败自动回滚）。
     */
    public boolean syncScope(Set<String> targetPkgs) {
        FrameworkDetector.Info fw = FrameworkDetector.detect();
        if (fw.flavor == FrameworkDetector.FLAVOR_NONE) {
            Log.e(TAG, "syncScope: framework not detected"); return false;
        }

        // ---- Vector：cli 直写（守护进程内部处理，零重启）----
        if (fw.cliWritesUsable()) {
            boolean ok = applyViaCli(fw.cli, targetPkgs);
            Log.i(TAG, "syncScope via cli (Vector): " + ok
                    + ", targets=" + (targetPkgs == null ? 0 : targetPkgs.size()));
            return ok;
        }

        // ---- 官方：直写库 + 重启守护进程 ----
        Log.i(TAG, "syncScope via direct-write+restart (official LSPosed)");
        Set<String> fullScope = new LinkedHashSet<>();
        fullScope.add("system"); // 系统框架只写 system（不写 android）
        if (targetPkgs != null) fullScope.addAll(targetPkgs);

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

        boolean restarted = stopWriteBackAndRestart(fw);
        cleanupStage();
        Log.i(TAG, "syncScope: edited=true restarted=" + restarted);
        return restarted;
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
     * 完整重启闭环（全部在一个 root 会话内）：
     * 1. SIGTERM 停旧守护进程（超时 SIGKILL）；
     * 2. 拷回新库（权限/restorecon 与 live 一致）+ sync；
     * 3. setsid 起新守护进程（复刻官方 service.sh 的 daemon --force）；
     * 4. lspctl status 轮询直到就绪（约 90 秒，覆盖首次 dex2oat）；
     * 5. 成功 RESTART_OK；失败则恢复旧库、重新拉起，输出 RESTART_FAIL（不留下半激活）。
     */
    private boolean stopWriteBackAndRestart(FrameworkDetector.Info fw) {
        String daemon = fw.moduleDir + "daemon";
        String lspctl = fw.moduleDir + "lspctl";
        String script =
                "set +e\n" +
                "for p in $(pidof lspd); do kill -TERM \"$p\" 2>/dev/null; done\n" +
                "i=0\n" +
                "while pidof lspd >/dev/null 2>&1; do\n" +
                "  i=$((i+1))\n" +
                "  if [ $i -ge 25 ]; then\n" +
                "    for p in $(pidof lspd); do kill -KILL \"$p\" 2>/dev/null; done\n" +
                "    sleep 0.5; break\n" +
                "  fi\n" +
                "  sleep 0.2\n" +
                "done\n" +
                "echo OLD_STOPPED\n" +
                "cp '" + SDB + "' '" + LIVE_DB + "'\n" +
                "rm -f '" + LIVE_DB + "-wal' '" + LIVE_DB + "-shm'\n" +
                "chmod 660 '" + LIVE_DB + "' 2>/dev/null\n" +
                "chown root:root '" + LIVE_DB + "' 2>/dev/null\n" +
                "restorecon -F '" + LIVE_DB + "' 2>/dev/null\n" +
                "sync\n" +
                "echo DB_WRITTEN\n" +
                "setsid sh '" + daemon + "' --force </dev/null >/dev/null 2>&1 &\n" +
                "echo NEW_LAUNCHED\n" +
                "i=0\n" +
                "ok=0\n" +
                "until '" + lspctl + "' status >/dev/null 2>&1; do\n" +
                "  i=$((i+1))\n" +
                "  if [ $i -ge 60 ]; then break; fi\n" +
                "  sleep 1\n" +
                "done\n" +
                "if pidof lspd >/dev/null 2>&1 && '" + lspctl + "' status >/dev/null 2>&1; then ok=1; fi\n" +
                "if [ $ok -eq 1 ]; then echo RESTART_OK; exit 0; fi\n" +
                "echo RESTART_FAIL, restoring previous db\n" +
                "for p in $(pidof lspd); do kill -KILL \"$p\" 2>/dev/null; done\n" +
                "sleep 0.5\n" +
                "cp '" + BACKUP_DB + "' '" + LIVE_DB + "'\n" +
                "chmod 660 '" + LIVE_DB + "' 2>/dev/null\n" +
                "chown root:root '" + LIVE_DB + "' 2>/dev/null\n" +
                "restorecon -F '" + LIVE_DB + "' 2>/dev/null\n" +
                "sync\n" +
                "setsid sh '" + daemon + "' --force </dev/null >/dev/null 2>&1 &\n" +
                "i=0\n" +
                "until '" + lspctl + "' status >/dev/null 2>&1; do\n" +
                "  i=$((i+1))\n" +
                "  if [ $i -ge 60 ]; then break; fi\n" +
                "  sleep 1\n" +
                "done\n";
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

    /** 确保守护进程存活（App 启动时调用）：已存活什么都不做；已死则安全拉起并轮询确认。 */
    public boolean ensureDaemon() {
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
                "setsid sh '" + daemon + "' --force </dev/null >/dev/null 2>&1 &\n" +
                "i=0\n" +
                "until '" + lspctl + "' status >/dev/null 2>&1; do\n" +
                "  i=$((i+1))\n" +
                "  if [ $i -ge 60 ]; then break; fi\n" +
                "  sleep 1\n" +
                "done\n" +
                "if pidof lspd >/dev/null 2>&1 && '" + lspctl + "' status >/dev/null 2>&1; then\n" +
                "  echo DAEMON_UP\n" +
                "else echo DAEMON_DOWN\nfi";
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
