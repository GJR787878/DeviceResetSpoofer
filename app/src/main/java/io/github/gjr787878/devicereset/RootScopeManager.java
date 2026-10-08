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
 * Root 作用域直写管理器。
 *
 * <p>在已授予 root 的设备上，无需打开 LSPosed/Vector 管理器、也无需在通知上点“批准”，
 * 直接把目标应用写入框架的作用域数据库 {@code modules_config.db}，并自动重启 lspd 守护进程
 * 使其立即重新读取配置，再强制停止目标应用 —— 实现“勾上即生效、零点击”。</p>
 *
 * <p>关键实现要点（均有框架源码依据）：</p>
 * <ul>
 *   <li>现代框架（Vector/LSPosed，API≥100）使用 <b>mid 新 schema</b>：
 *       {@code modules(mid,module_pkg_name,apk_path,enabled,...)}、
 *       {@code scope(mid,app_pkg_name,user_id)}。旧的 {@code modules_state} 与
 *       {@code scope.module_pkg_name} 已不存在（仅作为迁移来源）。</li>
 *   <li>暂存目录必须放在<b>模块私有 filesDir</b>：root 拷出到 /data/local/tmp 后，
 *       untrusted_app 受 SELinux 限制打不开（shell_data_file），会静默失败；
 *       拷到 filesDir 后再 chown 回本应用 uid、restorecon 成 app_data_file 即可正常读写。</li>
 *   <li>不依赖设备上是否有 sqlite3：root 拷出 → 应用内 SQLite 修改 → root 拷回
 *       （覆盖同名文件以保留属主/权限/SELinux 上下文）。</li>
 *   <li>框架在守护进程启动时读取配置，写完库必须重启守护进程；重启复刻 reRunDaemon：
 *       {@code killall lspd} 后运行框架模块目录 {@code service.sh}。</li>
 * </ul>
 */
public final class RootScopeManager {

    private static final String TAG = "RootScopeManager";
    private static final String MODULE_PKG = "io.github.gjr787878.devicereset";
    private static final int USER_ID = 0;

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

    /** 定位框架 magisk 模块目录（含 daemon 与 service.sh），用于重启守护进程。 */
    public String findModuleDir() {
        if (moduleDirCached != null) return moduleDirCached;
        String script =
                "for d in /data/adb/modules/*/ /data/adb/modules_update/*/; do\n" +
                "  if [ -f \"$d/daemon\" ] && [ -f \"$d/service.sh\" ]; then\n" +
                "    case \"$d\" in *lsposed*|*lspd*|*vector*) echo \"$d\"; break;; esac\n" +
                "  fi\n" +
                "done\n" +
                "if [ ! -d \"$d\" ]; then\n" +
                "  for d in /data/adb/modules/*/; do\n" +
                "    [ -f \"$d/daemon\" ] && [ -f \"$d/service.sh\" ] && { echo \"$d\"; break; }\n" +
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
            // 打开前删除残留 WAL/SHM，强制 checkpoint 到主库（避免 WAL 不完整导致打开失败）
            runRoot("rm -f '" + SWAL + "' '" + SSHM + "' 2>/dev/null");
            sql = SQLiteDatabase.openDatabase(SDB, null, SQLiteDatabase.OPEN_READWRITE);
            Long mid = getModuleMid(sql);
            Log.i(TAG, "readScope: mid=" + mid);
            if (mid == null) {
                // 模块未在管理器启用：返回空集合（非 null），调用方据此走 rootMode 并在 syncScope 时自动启用
                Log.i(TAG, "readScope: module not in modules table, returning empty set (rootMode will auto-enable)");
                return scope;
            }
            try (Cursor c = sql.rawQuery(
                    "SELECT app_pkg_name FROM scope WHERE mid=? AND user_id=?",
                    new String[]{String.valueOf(mid), String.valueOf(USER_ID)})) {
                while (c.moveToNext()) scope.add(c.getString(0));
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

    private Long getModuleMid(SQLiteDatabase sql) {
        try (Cursor c = sql.rawQuery(
                "SELECT mid FROM modules WHERE module_pkg_name=?",
                new String[]{MODULE_PKG})) {
            if (c.moveToFirst()) return c.getLong(0);
        }
        return null;
    }

    // ============================== 写入作用域（全量同步） ==============================

    /**
     * 把本模块作用域全量同步为 {@code targetPkgs}，并确保模块已启用；随后重启守护进程。
     */
    public boolean syncScope(Set<String> targetPkgs) {
        String db = findDbPath();
        if (db == null) { Log.e(TAG, "db not found"); return false; }

        // 1) 先停守护进程，避免并发写 / WAL 竞争
        killDaemon();

        // 2) 拷出到私有暂存目录
        if (!stageFromDevice(db)) { restartDaemon(); return false; }

        // 3) 应用内 SQLite 修改（新 mid schema）
        boolean edited;
        SQLiteDatabase sql = null;
        try {
            sql = SQLiteDatabase.openDatabase(SDB, null, SQLiteDatabase.OPEN_READWRITE);
            sql.beginTransaction();
            try {
                Long mid = getModuleMid(sql);
                if (mid == null) {
                    // 模块记录缺失（从未在管理器启用）：补一条 enabled 记录
                    String apk = context.getPackageManager()
                            .getApplicationInfo(MODULE_PKG, 0).sourceDir;
                    sql.execSQL(
                            "INSERT INTO modules (module_pkg_name, apk_path, enabled, auto_include) " +
                            "VALUES (?,?,1,0)",
                            new Object[]{MODULE_PKG, apk});
                    mid = getModuleMid(sql);
                } else {
                    sql.execSQL("UPDATE modules SET enabled=1 WHERE mid=?",
                            new Object[]{mid});
                }
                if (mid == null) throw new IllegalStateException("no mid");
                // 全量替换主用户(0)作用域
                sql.execSQL("DELETE FROM scope WHERE mid=? AND user_id=?",
                        new Object[]{mid, USER_ID});
                for (String pkg : targetPkgs) {
                    if (pkg == null || pkg.isEmpty()) continue;
                    sql.execSQL(
                            "INSERT INTO scope (mid, app_pkg_name, user_id) VALUES (?,?,?)",
                            new Object[]{mid, pkg, USER_ID});
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

        // 4) 拷回（覆盖同名文件，保留属主/权限/SELinux）；清旧 wal/shm
        String copyBack =
                "cp '" + SDB + "' '" + db + "'\n" +
                "rm -f '" + db + "-wal' '" + db + "-shm'\n" +
                "chmod 660 '" + db + "' 2>/dev/null\n" +
                "chown root:root '" + db + "' 2>/dev/null\n" +
                "restorecon -F '" + db + "' 2>/dev/null\n" +
                "echo BACK_OK";
        Result back = runRoot(copyBack);
        cleanupStage();

        // 5) 无论成败都重启守护进程
        boolean restarted = restartDaemon();
        return edited && back.output.contains("BACK_OK") && restarted;
    }

    private void cleanupStage() {
        runRoot("rm -rf '" + stageDir.getAbsolutePath() + "'");
    }

    // ============================== 守护进程 / 目标应用 ==============================

    /** 停止 lspd 守护进程（轮询确认退出，最多约 3 秒）。 */
    public void killDaemon() {
        runRoot(
                "killall lspd 2>/dev/null\n" +
                "i=0\n" +
                "while pidof lspd >/dev/null 2>&1; do\n" +
                "  i=$((i+1)); [ $i -ge 15 ] && break\n" +
                "  sleep 0.2\n" +
                "done\n" +
                "echo KILLED");
    }

    /**
     * 重启 lspd 守护进程：运行框架模块目录的 service.sh（复刻 reRunDaemon）。
     * 其内部 {@code unshare ... sh -c "$MODDIR/daemon --from-service ...&"} 拉起守护进程。
     */
    public boolean restartDaemon() {
        String dir = findModuleDir();
        if (dir == null) { Log.e(TAG, "module dir not found"); return false; }
        String sh = dir + "service.sh";
        String busybox = "/data/adb/magisk/busybox";
        String shell = "export PATH=/data/adb/magisk:/system/bin:/system/xbin:$PATH; " +
                "[ -x " + busybox + " ] && BB=\"" + busybox + "\" || BB=sh; " +
                "export ASH_STANDALONE=1; " +
                "$BB sh '" + sh + "' --system-server-max-retry=-1; " +
                "i=0; " +
                "until pidof lspd >/dev/null 2>&1; do " +
                "  i=$((i+1)); [ $i -ge 40 ] && break; " +
                "  sleep 0.2; " +
                "done; " +
                "(pidof lspd >/dev/null && echo DAEMON_UP) || echo DAEMON_DOWN";
        Result r = runRoot(shell);
        boolean up = r.output.contains("DAEMON_UP");
        Log.i(TAG, "restartDaemon: " + r.output);
        return up;
    }

    /** 强制停止目标应用（root），下次打开即为注入后的全新进程。 */
    public boolean forceStop(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Result r = runRoot("am force-stop '" + pkg + "'; echo FS_DONE");
        return r.output.contains("FS_DONE");
    }
}
