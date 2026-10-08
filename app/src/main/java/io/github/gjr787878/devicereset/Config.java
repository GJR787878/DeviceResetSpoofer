package io.github.gjr787878.devicereset;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;

import java.util.HashSet;
import java.util.Set;

/**
 * 模块配置管理。
 * 配置存在模块自己的SharedPreferences中，不在目标APP目录，
 * 所以清除目标APP数据不会影响模块配置。
 */
public class Config {
    private static final String PREFS_NAME = "devicereset_config";
    private static final String KEY_TARGET_PACKAGES = "target_packages";
    /** 已手动设置伪装身份的包名集合（身份主存储，模块通过 XSharedPreferences 读取，UI 无需 Root 读写） */
    private static final String KEY_IDENTITY_PACKAGES = "identity_packages";
    private static final String KEY_IDENTITY_PREFIX = "identity_";
    private static final String KEY_AUTO_RESET = "auto_reset_on_clear";
    private static final String KEY_HOOK_ANDROID_ID = "hook_android_id";
    private static final String KEY_HOOK_AD_ID = "hook_ad_id";
    private static final String KEY_HOOK_IMEI = "hook_imei";
    private static final String KEY_HOOK_BUILD = "hook_build_info";
    private static final String KEY_HOOK_MAC = "hook_mac";
    private static final String KEY_HOOK_GSF = "hook_gsf_id";
    private static final String KEY_HOOK_CARRIER = "hook_carrier";

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ===== LSPosed 作用域自动同步（root 写库，免开 LSPosed 管理器） =====
    private static final String KEY_SCOPE_SYNCED = "lsp_scope_synced";
    private static final String MODULE_PKG = "io.github.gjr787878.devicereset";
    private static final String LSPD_DB = "/data/adb/lspd/config/modules_config.db";

    /**
     * 把目标列表 + 模块自身 + 系统框架 同步进 LSPosed 作用域（modules_config.db）。
     * LSPosed 的配置库是标准明文 SQLite（本机已有实证：modules/scope 表结构完整、模块已启用）。
     * 流程：root cp db(+wal/shm) → 模块私有目录 → SQLiteDatabase 打开（自动合并 WAL）
     *      → INSERT scope 行（模块自身/system/android/所有目标，user_id=0）→ close
     *      → root cp 合并后的主库回原路径 + chmod 600 + 删除手机侧 wal/shm
     * 写完需重启手机一次，LSPosed daemon 启动时读取新作用域生效。
     * 已同步过且内容未变化则跳过（避免每次进页面都 root 操作）。
     * 结果写入 getExternalFilesDir/lsp_sync.log（导出日志附带）。
     */
    public static void syncScopeToLSPosed(Context context, Set<String> targets) {
        final Context ctx = context.getApplicationContext();
        StringBuilder log = new StringBuilder("=== LSPosed Auto-Sync Log ===\n");
        java.io.File logFile = new java.io.File(ctx.getExternalFilesDir(null), "lsp_sync.log");

        // 同步成功后提示（主页顶部横幅会显示「已同步作用域」）
        setScopeSyncPending(ctx, true);
        log.append("time=").append(System.currentTimeMillis()).append("\n");
        try {
            // 1. 内容未变化则跳过
            Set<String> synced = getPrefs(ctx).getStringSet(KEY_SCOPE_SYNCED, new HashSet<>());
            Set<String> want = new HashSet<>();
            if (targets != null) want.addAll(targets);
            want.add(MODULE_PKG);
            want.add("system");
            want.add("android");
            if (synced.equals(want)) {
                log.append("UNCHANGED: skip (scope already in sync)\n");
                writeLog(logFile, log.toString());
                return;
            }
            // 2. 检查 root
            String rootOk = runSu("id -u");
            if (!"0".equals(rootOk.trim())) {
                log.append("NO_ROOT: cannot sync scope\n");
                writeLog(logFile, log.toString());
                return;
            }
            // 3. root cp db(+wal/shm) 到模块私有目录
            java.io.File tmpDir = new java.io.File(ctx.getFilesDir(), "lspd_tmp");
            runSu("rm -rf '" + tmpDir.getAbsolutePath() + "' && mkdir -p '" + tmpDir.getAbsolutePath() + "'");
            String cpDb = runSu("cp " + LSPD_DB + " '" + tmpDir.getAbsolutePath() + "/' 2>&1; "
                    + "cp " + LSPD_DB + "-wal '" + tmpDir.getAbsolutePath() + "/' 2>&1; "
                    + "cp " + LSPD_DB + "-shm '" + tmpDir.getAbsolutePath() + "/' 2>&1; "
                    + "chmod 666 '" + tmpDir.getAbsolutePath() + "'/* 2>&1; echo CP_OK");
            log.append("cp: ").append(cpDb.trim()).append("\n");
            java.io.File localDb = new java.io.File(tmpDir, "modules_config.db");
            if (!localDb.exists() || !localDb.canRead()) {
                log.append("CP_FAIL: db not readable locally\n");
                writeLog(logFile, log.toString());
                return;
            }
            // 4. SQLiteDatabase 打开（自动合并 WAL）并写入 scope
            SQLiteDatabase db = SQLiteDatabase.openDatabase(localDb.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            try {
                java.util.List<String> tables = new java.util.ArrayList<>();
                android.database.Cursor c = db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('modules','modules_state','scope')", null);
                while (c.moveToNext()) tables.add(c.getString(0));
                c.close();
                log.append("tables: ").append(tables).append("\n");
                if (!tables.contains("scope")) {
                    log.append("NO_SCOPE_TABLE: this LSPosed db has no scope table\n");
                    writeLog(logFile, log.toString());
                    db.close();
                    return;
                }
                // 检测表结构：新版 Vector/LSPosed 用 modules(mid) + scope(mid, app_pkg_name)
                // 旧版用 modules_state + scope(module_pkg_name, app_pkg_name)
                boolean newSchema = tables.contains("modules");
                if (newSchema) {
                    // 新版：先查 mid，不存在则插入 modules 表
                    int mid = -1;
                    android.database.Cursor mc = db.rawQuery(
                            "SELECT mid FROM modules WHERE module_pkg_name=? LIMIT 1",
                            new String[]{MODULE_PKG});
                    if (mc.moveToFirst()) mid = mc.getInt(0);
                    mc.close();
                    if (mid < 0) {
                        // 模块未在 LSPosed 启用，插入 modules 表并启用
                        db.execSQL("INSERT OR IGNORE INTO modules (module_pkg_name, apk_path, enabled, auto_include) VALUES (?, ?, 1, 0)",
                                new Object[]{MODULE_PKG, ctx.getPackageCodePath()});
                        mc = db.rawQuery("SELECT mid FROM modules WHERE module_pkg_name=? LIMIT 1",
                                new String[]{MODULE_PKG});
                        if (mc.moveToFirst()) mid = mc.getInt(0);
                        mc.close();
                    }
                    log.append("new schema: mid=").append(mid).append("\n");
                    if (mid >= 0) {
                        // 确保模块启用
                        db.execSQL("UPDATE modules SET enabled=1 WHERE mid=?", new Object[]{mid});
                        // 写入 scope：system + android + self + targets
                        db.execSQL("INSERT OR IGNORE INTO scope (mid, app_pkg_name, user_id) VALUES (?, ?, 0)",
                                new Object[]{mid, "system"});
                        db.execSQL("INSERT OR IGNORE INTO scope (mid, app_pkg_name, user_id) VALUES (?, ?, 0)",
                                new Object[]{mid, "android"});
                        db.execSQL("INSERT OR IGNORE INTO scope (mid, app_pkg_name, user_id) VALUES (?, ?, 0)",
                                new Object[]{mid, MODULE_PKG});
                        int n = 0;
                        for (String p : targets) {
                            if (p == null || p.isEmpty() || MODULE_PKG.equals(p)) continue;
                            db.execSQL("INSERT OR IGNORE INTO scope (mid, app_pkg_name, user_id) VALUES (?, ?, 0)",
                                    new Object[]{mid, p});
                            n++;
                        }
                        log.append("new schema scope rows: system/android/self + ").append(n).append(" targets\n");
                    }
                } else {
                    // 旧版表结构
                    db.execSQL("INSERT OR REPLACE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES (?, ?, 0)",
                            new Object[]{MODULE_PKG, "system"});
                    db.execSQL("INSERT OR REPLACE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES (?, ?, 0)",
                            new Object[]{MODULE_PKG, "android"});
                    db.execSQL("INSERT OR REPLACE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES (?, ?, 0)",
                            new Object[]{MODULE_PKG, MODULE_PKG});
                    int n = 0;
                    for (String p : targets) {
                        if (p == null || p.isEmpty() || MODULE_PKG.equals(p)) continue;
                        db.execSQL("INSERT OR REPLACE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES (?, ?, 0)",
                                new Object[]{MODULE_PKG, p});
                        n++;
                    }
                    log.append("old schema scope rows: system/android/self + ").append(n).append(" targets\n");
                }
            } finally {
                db.close(); // close = checkpoint WAL 到主库
            }
            // 5. 写回 + 清手机侧 wal（避免旧 WAL 覆盖新主库）
            String back = runSu("cp '" + tmpDir.getAbsolutePath() + "/modules_config.db' " + LSPD_DB + " 2>&1; "
                    + "chmod 600 " + LSPD_DB + " 2>&1; "
                    + "rm -f " + LSPD_DB + "-wal " + LSPD_DB + "-shm 2>&1; echo BACK_OK");
            log.append("writeback: ").append(back.trim()).append("\n");
            // 6. 记录已同步集合
            getPrefs(ctx).edit().putStringSet(KEY_SCOPE_SYNCED, want).apply();
            log.append("SYNC_OK (reboot once to take effect)\n");
        } catch (Throwable e) {
            log.append("SYNC_ERROR: ").append(e.getMessage()).append("\n");
        }
        writeLog(logFile, log.toString());
    }

    private static String runSu(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + "\nexit\n");
            os.flush();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            p.waitFor();
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static void writeLog(java.io.File f, String content) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(content);
            w.close();
        } catch (Throwable ignored) {}
    }

    // ===== LSPosed 数据库真实状态读取（主页「框架连接」显示用，不依赖 libxposed service） =====
    public static final class ScopeDbState {
        public boolean dbOk = false;      // 数据库可读
        public boolean enabled = false;   // 模块在 LSPosed 已启用
        public boolean hasSelf = false;   // 模块自身在作用域（libxposed service 能连接的前提）
        public boolean hasSystem = false; // 系统框架在作用域（全局注入）
        public java.util.Set<String> targets = new java.util.HashSet<>(); // 作用域中的目标包名
    }

    /** root 拷贝 db 到私有目录用 SQLiteDatabase 读取真实状态（不写库）。
     * 返回 null 表示无 root / db 不可读。 */
    public static ScopeDbState readLSPosedDbState(Context context) {
        ScopeDbState st = new ScopeDbState();
        try {
            Context ctx = context.getApplicationContext();
            if (!"0".equals(runSu("id -u").trim())) return null;
            java.io.File tmpDir = new java.io.File(ctx.getFilesDir(), "lspd_read");
            runSu("rm -rf '" + tmpDir.getAbsolutePath() + "' && mkdir -p '" + tmpDir.getAbsolutePath() + "'");
            runSu("cp " + LSPD_DB + " '" + tmpDir.getAbsolutePath() + "/' 2>/dev/null; "
                    + "cp " + LSPD_DB + "-wal '" + tmpDir.getAbsolutePath() + "/' 2>/dev/null; "
                    + "cp " + LSPD_DB + "-shm '" + tmpDir.getAbsolutePath() + "/' 2>/dev/null; "
                    + "chmod 666 '" + tmpDir.getAbsolutePath() + "'/* 2>/dev/null; echo OK");
            java.io.File localDb = new java.io.File(tmpDir, "modules_config.db");
            if (!localDb.exists() || !localDb.canRead()) return null;
            SQLiteDatabase db = SQLiteDatabase.openDatabase(localDb.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            try {
                // 检测表结构：新版有 modules 表，旧版有 modules_state 表
                boolean hasModules = false;
                boolean hasModulesState = false;
                android.database.Cursor tc = db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('modules','modules_state')", null);
                while (tc.moveToNext()) {
                    String n = tc.getString(0);
                    if ("modules".equals(n)) hasModules = true;
                    if ("modules_state".equals(n)) hasModulesState = true;
                }
                tc.close();

                if (hasModules) {
                    // 新版 Vector/LSPosed: modules(mid, module_pkg_name, enabled) + scope(mid, app_pkg_name, user_id)
                    android.database.Cursor c = db.rawQuery(
                            "SELECT mid, enabled FROM modules WHERE module_pkg_name=? LIMIT 1",
                            new String[]{MODULE_PKG});
                    int mid = -1;
                    if (c.moveToFirst()) {
                        mid = c.getInt(0);
                        st.enabled = c.getInt(1) == 1;
                    }
                    c.close();
                    if (mid >= 0) {
                        c = db.rawQuery(
                                "SELECT app_pkg_name FROM scope WHERE mid=? AND user_id=0",
                                new String[]{String.valueOf(mid)});
                        while (c.moveToNext()) {
                            String p = c.getString(0);
                            if (MODULE_PKG.equals(p)) st.hasSelf = true;
                            else if ("system".equals(p) || "android".equals(p)) st.hasSystem = true;
                            else st.targets.add(p);
                        }
                        c.close();
                    }
                    st.dbOk = true;
                } else if (hasModulesState) {
                    // 旧版 LSPosed: modules_state + scope(module_pkg_name)
                    android.database.Cursor c = db.rawQuery(
                            "SELECT enabled FROM modules_state WHERE module_pkg_name=? AND user_id=0",
                            new String[]{MODULE_PKG});
                    if (c.moveToFirst()) st.enabled = c.getInt(0) == 1;
                    c.close();
                    c = db.rawQuery("SELECT app_pkg_name FROM scope WHERE module_pkg_name=?",
                            new String[]{MODULE_PKG});
                    while (c.moveToNext()) {
                        String p = c.getString(0);
                        if (MODULE_PKG.equals(p)) st.hasSelf = true;
                        else if ("system".equals(p)) st.hasSystem = true;
                        else if (!"android".equals(p)) st.targets.add(p);
                    }
                    c.close();
                    st.dbOk = true;
                }
            } finally {
                db.close();
            }
            runSu("rm -rf '" + tmpDir.getAbsolutePath() + "'");
        } catch (Throwable ignored) {
        }
        return st;
    }

    /** 记录「作用域已同步、等待重启生效」标记（主页顶部横幅显示提示） */
    private static final String KEY_SYNC_PENDING = "lsp_scope_sync_pending";
    public static void setScopeSyncPending(Context context, boolean pending) {
        getPrefs(context.getApplicationContext()).edit().putBoolean(KEY_SYNC_PENDING, pending).apply();
    }
    public static boolean isScopeSyncPending(Context context) {
        return getPrefs(context.getApplicationContext()).getBoolean(KEY_SYNC_PENDING, false);
    }

    /**
     * 获取所有目标应用包名。
     * 注意：在Xposed hook进程中调用时，context是目标APP的context，
     * 无法直接读取模块的SharedPreferences。所以目标包名列表通过
     * XSharedPreferences读取，这里提供给配置界面使用。
     */
    public static Set<String> getTargetPackages(Context context) {
        return getPrefs(context).getStringSet(KEY_TARGET_PACKAGES, new HashSet<>());
    }

    public static void setTargetPackages(Context context, Set<String> packages) {
        getPrefs(context).edit().putStringSet(KEY_TARGET_PACKAGES, packages).apply();
        updateTargetsFile(context, packages);
    }

    public static void addTargetPackage(Context context, String packageName) {
        Set<String> packages = new HashSet<>(getTargetPackages(context));
        packages.add(packageName);
        setTargetPackages(context, packages);
    }

    public static void removeTargetPackage(Context context, String packageName) {
        Set<String> packages = new HashSet<>(getTargetPackages(context));
        packages.remove(packageName);
        setTargetPackages(context, packages);
    }

    public static boolean isTargetPackage(Context context, String packageName) {
        return getTargetPackages(context).contains(packageName);
    }

    /**
     * 把目标列表同步到模块私有 files/targets.txt（chmod 666，任何进程可读）。
     * 用途：LSPosed 勾选「系统框架」后模块注入所有进程，MainHook 读此文件
     * 判断目标，实现「应用内选目标即生效」，完全不依赖 LSPosed 作用域同步。
     */
    private static void updateTargetsFile(Context context, Set<String> packages) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String p : packages) sb.append(p).append('\n');
            java.io.File f = new java.io.File(context.getFilesDir(), "targets.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            f.setReadable(true, false); // chmod 644，hook 进程可读
        } catch (Throwable ignored) {
        }
        // 关键：目录链也必须放开执行/读权限，否则被注入的目标进程进不了
        // /data/data/模块/files 目录，readTargetsFile 必败 → 全局注入全部跳过。
        // （有 root 才有效；无 root 时 targets 机制不可用，静默跳过不打扰）
        try {
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("chmod 755 /data/data/" + context.getPackageName() + "\n");
            os.writeBytes("chmod 755 /data/data/" + context.getPackageName() + "/files\n");
            os.writeBytes("chmod 666 /data/data/" + context.getPackageName() + "/files/targets.txt\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();
        } catch (Throwable ignored) {
        }
    }

    // ===== 身份主存储（模块配置，手动触发写入，UI 无需 Root） =====

    /** 已手动设置伪装身份的包名集合 */
    public static Set<String> getIdentityPackages(Context context) {
        return getPrefs(context).getStringSet(KEY_IDENTITY_PACKAGES, new HashSet<>());
    }

    public static boolean isIdentityConfigured(Context context, String packageName) {
        return getIdentityPackages(context).contains(packageName);
    }

    /** 读取某应用的伪装身份 JSON（未设置返回 null） */
    public static String getIdentity(Context context, String packageName) {
        return getPrefs(context).getString(KEY_IDENTITY_PREFIX + packageName, null);
    }

    /** 写入某应用的伪装身份 JSON（手动触发：点随机/保存） */
    public static void setIdentity(Context context, String packageName, String json) {
        Set<String> pkgs = new HashSet<>(getIdentityPackages(context));
        pkgs.add(packageName);
        getPrefs(context).edit()
                .putStringSet(KEY_IDENTITY_PACKAGES, pkgs)
                .putString(KEY_IDENTITY_PREFIX + packageName, json)
                .apply();
    }

    /** 删除某应用的伪装身份（长按删除目标时调用） */
    public static void removeIdentity(Context context, String packageName) {
        Set<String> pkgs = new HashSet<>(getIdentityPackages(context));
        pkgs.remove(packageName);
        getPrefs(context).edit()
                .putStringSet(KEY_IDENTITY_PACKAGES, pkgs)
                .remove(KEY_IDENTITY_PREFIX + packageName)
                .apply();
    }

    // 各开关的getter/setter
    public static boolean isAutoReset(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTO_RESET, true);
    }

    public static void setAutoReset(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_RESET, value).apply();
    }

    public static boolean isHookAndroidId(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_ANDROID_ID, true);
    }

    public static void setHookAndroidId(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_ANDROID_ID, value).apply();
    }

    public static boolean isHookAdId(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_AD_ID, true);
    }

    public static void setHookAdId(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_AD_ID, value).apply();
    }

    public static boolean isHookImei(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_IMEI, true);
    }

    public static void setHookImei(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_IMEI, value).apply();
    }

    public static boolean isHookBuild(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_BUILD, true);
    }

    public static void setHookBuild(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_BUILD, value).apply();
    }

    public static boolean isHookMac(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_MAC, true);
    }

    public static void setHookMac(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_MAC, value).apply();
    }

    public static boolean isHookGsf(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_GSF, true);
    }

    public static void setHookGsf(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_GSF, value).apply();
    }

    public static boolean isHookCarrier(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_CARRIER, true);
    }

    public static void setHookCarrier(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_CARRIER, value).apply();
    }
}
