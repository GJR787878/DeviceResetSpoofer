package io.github.gjr787878.devicereset.ui;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.gjr787878.devicereset.GlassButtonDrawable;
import io.github.gjr787878.devicereset.R;
import io.github.gjr787878.devicereset.xposed.Identity;
import io.github.gjr787878.devicereset.xposed.IdentityGenerator;

public class AppPickerActivity extends AppCompatActivity {

    private static final String MODULE_PKG = "io.github.gjr787878.devicereset";
    private static final String MODULE_PKG_SHORT = "devicereset";
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[([^\\[\\]]{2,5000})\\]");
    private static final String PREFS_LANG = "app_language";
    private static final String LANG_ZH = "zh";
    private static final String LANG_EN = "en";
    private static final String LANG_RU = "ru";

    private String currentLang = LANG_ZH;
    private RecyclerView rvApps;
    private TextView tvLoading;
    private TextView tvEmpty;
    private TextView tvCount;
    private TextView tvDebug;
    private AppAdapter adapter;
    private final List<AppItem> appList = new ArrayList<>();
    private final StringBuilder debugLog = new StringBuilder();

    static class AppItem {
        String packageName;
        String appName;
        Drawable icon;
        boolean hasIdentity;
        String identityJson;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("devicereset_ui", MODE_PRIVATE);
        currentLang = prefs.getString(PREFS_LANG, LANG_ZH);

        setContentView(R.layout.activity_app_picker);

        tvLoading = findViewById(R.id.tv_loading);
        tvEmpty = findViewById(R.id.tv_empty);
        tvCount = findViewById(R.id.tv_count);
        tvDebug = findViewById(R.id.tv_debug);
        rvApps = findViewById(R.id.rv_apps);
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        rvApps.setNestedScrollingEnabled(false);
        adapter = new AppAdapter();
        rvApps.setAdapter(adapter);

        updateLanguage();
        loadApps();
    }

    private void updateLanguage() {
        boolean zh = LANG_ZH.equals(currentLang);
        boolean en = LANG_EN.equals(currentLang);
        if (zh) {
            setTitle("选择应用");
            tvLoading.setText("正在扫描...");
        } else if (en) {
            setTitle("Select App");
            tvLoading.setText("Scanning...");
        } else {
            setTitle("Выбор приложения");
            tvLoading.setText("Сканирование...");
        }
    }

    private void log(String msg) {
        debugLog.append(msg).append("\n");
    }

    /** Root预热：执行简单su命令确保权限就绪，最多等待5秒 */
    private void ensureRootReady() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Process su = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                os.writeBytes("echo root_ready\n");
                os.writeBytes("exit\n");
                os.flush();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(su.getInputStream()));
                String line = reader.readLine();
                reader.close();
                su.waitFor();
                if ("root_ready".equals(line)) {
                    log("Root就绪 (attempt " + (attempt+1) + ")");
                    return;
                }
            } catch (Throwable e) {
                log("Root预热失败 attempt " + (attempt+1) + ": " + e.getMessage());
            }
            try { Thread.sleep(500); } catch (Throwable ignored) {}
        }
        log("Root预热超时，继续执行");
    }

    private void loadApps() {
        debugLog.setLength(0);
        new Thread(() -> {
            // 0. Root预热：确保root权限就绪后再读取文件
            ensureRootReady();
            // 1. 从 LSPosed 配置读取作用域
            Set<String> scopePkgs = readLSPosedScope();
            log("作用域读取结果: " + scopePkgs.size() + " 个 -> " + scopePkgs);

            // 2. 扫描有哨兵文件的应用
            Set<String> identityPkgs = scanIdentityPackages();
            identityPkgs.remove(MODULE_PKG);
            log("哨兵文件应用: " + identityPkgs.size() + " 个 -> " + identityPkgs);

            // 合并
            Set<String> allPkgs = new HashSet<>();
            allPkgs.addAll(scopePkgs);
            allPkgs.addAll(identityPkgs);
            allPkgs.remove(MODULE_PKG);

            PackageManager pm = getPackageManager();
            Drawable defaultIcon = getResources().getDrawable(android.R.drawable.sym_def_app_icon);
            List<AppItem> items = new ArrayList<>();
            for (String pkg : allPkgs) {
                AppItem item = new AppItem();
                item.packageName = pkg;
                item.appName = pkg;
                item.icon = defaultIcon;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
                    log("图标调试 " + pkg + " sourceDir=" + ai.sourceDir + " publicSourceDir=" + ai.publicSourceDir + " iconRes=" + ai.icon + " enabled=" + ai.enabled + " suspended=" + ((ai.flags & ApplicationInfo.FLAG_SUSPENDED) != 0));

                    // 名称
                    try {
                        CharSequence label = ai.loadLabel(pm);
                        if (label != null && !label.toString().equals(pkg)) {
                            item.appName = label.toString();
                        }
                    } catch (Throwable e) {
                        log("名称loadLabel失败: " + e.getMessage());
                    }

                    // 图标方式1：直接从APK文件读取（绕过应用挂起/休眠状态）
                    try {
                        String apkPath = ai.publicSourceDir != null ? ai.publicSourceDir : ai.sourceDir;
                        if (apkPath != null && new java.io.File(apkPath).exists()) {
                            android.content.pm.PackageInfo archiveInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA);
                            if (archiveInfo != null && archiveInfo.applicationInfo != null) {
                                archiveInfo.applicationInfo.sourceDir = apkPath;
                                archiveInfo.applicationInfo.publicSourceDir = apkPath;
                                Drawable icon = pm.getApplicationIcon(archiveInfo.applicationInfo);
                                if (icon != null) {
                                    item.icon = icon;
                                    log("图标方式1(APK)成功 " + pkg);
                                }
                            }
                        }
                    } catch (Throwable e) {
                        log("图标方式1(APK)失败 " + pkg + ": " + e.getMessage());
                    }

                    // 图标方式2：getResourcesForApplication
                    if (item.icon == defaultIcon) {
                        try {
                            android.content.res.Resources res = pm.getResourcesForApplication(ai);
                            if (ai.icon != 0) {
                                Drawable icon = res.getDrawable(ai.icon, null);
                                if (icon != null) {
                                    item.icon = icon;
                                    log("图标方式2(Resources)成功 " + pkg);
                                }
                            }
                        } catch (Throwable e) {
                            log("图标方式2(Resources)失败 " + pkg + ": " + e.getMessage());
                        }
                    }

                    // 图标方式3：pm.getApplicationIcon(pkg) String版本
                    if (item.icon == defaultIcon) {
                        try {
                            Drawable icon = pm.getApplicationIcon(pkg);
                            if (icon != null) {
                                item.icon = icon;
                                log("图标方式3(pm)成功 " + pkg);
                            }
                        } catch (Throwable e) {
                            log("图标方式3(pm)失败 " + pkg + ": " + e.getMessage());
                        }
                    }
                } catch (Throwable e) {
                    log("getApplicationInfo失败 " + pkg + ": " + e.getMessage());
                    // 兜底：应用可能在其他用户/工作profile，用root找APK路径
                    String apkPath = findApkPathViaRoot(pkg);
                    log("root查找APK " + pkg + " -> " + apkPath);
                    if (apkPath != null) {
                        try {
                            android.content.pm.PackageInfo archiveInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA);
                            if (archiveInfo != null && archiveInfo.applicationInfo != null) {
                                archiveInfo.applicationInfo.sourceDir = apkPath;
                                archiveInfo.applicationInfo.publicSourceDir = apkPath;
                                // 名称
                                try {
                                    CharSequence label = archiveInfo.applicationInfo.loadLabel(pm);
                                    if (label != null && !label.toString().equals(pkg)) {
                                        item.appName = label.toString();
                                    }
                                } catch (Throwable ignored) {}
                                // 图标
                                try {
                                    Drawable icon = pm.getApplicationIcon(archiveInfo.applicationInfo);
                                    if (icon != null) {
                                        item.icon = icon;
                                        log("从APK加载图标成功 " + pkg);
                                    }
                                } catch (Throwable e2) {
                                    log("从APK加载图标失败 " + pkg + ": " + e2.getMessage());
                                }
                            }
                        } catch (Throwable e2) {
                            log("getPackageArchiveInfo失败 " + pkg + ": " + e2.getMessage());
                        }
                    }
                }
                // 直接读取哨兵文件（不依赖扫描），并与原机真实值对比判断是否真的伪装了
                String json = readIdentityFile(pkg);
                if (json == null) {
                    // 重试一次（root刚就绪时第一次调用可能失败）
                    try { Thread.sleep(300); } catch (Throwable ignored) {}
                    json = readIdentityFile(pkg);
                }
                if (json != null) {
                    Identity id = Identity.fromJson(json);
                    if (id != null && isIdentitySpoofed(id)) {
                        item.identityJson = json;
                        item.hasIdentity = true;
                        log("检测到伪装值 " + pkg + " androidId=" + id.androidId + " brand=" + id.brand);
                    } else {
                        log("哨兵文件存在但值与原机相同或解析失败 " + pkg);
                    }
                } else {
                    log("未读取到哨兵文件 " + pkg);
                }
                items.add(item);
                log("已添加: " + pkg + " name=" + item.appName + " iconIsDefault=" + (item.icon == defaultIcon) + " hasIdentity=" + item.hasIdentity);
            }
            log("最终应用列表: " + items.size() + " 个");

            items.sort((a, b) -> {
                if (a.hasIdentity != b.hasIdentity) return a.hasIdentity ? -1 : 1;
                return a.appName.compareToIgnoreCase(b.appName);
            });

            final List<AppItem> finalItems = items;
            runOnUiThread(() -> {
                tvLoading.setVisibility(View.GONE);
                appList.clear();
                appList.addAll(finalItems);
                adapter.notifyDataSetChanged();
                if (finalItems.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    if (LANG_ZH.equals(currentLang)) {
                        tvEmpty.setText("未找到作用域应用。\n请在 LSPosed 中勾选目标应用并重启。");
                    } else if (LANG_EN.equals(currentLang)) {
                        tvEmpty.setText("No scoped apps found.\nPlease enable the module for target apps in LSPosed and reboot.");
                    } else {
                        tvEmpty.setText("Приложения в области действия не найдены.\nВключите модуль для целевых приложений в LSPosed и перезагрузите устройство.");
                    }
                    tvDebug.setText(debugLog.toString());
                    tvDebug.setVisibility(View.VISIBLE);
                    rvApps.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    tvDebug.setVisibility(View.GONE);
                    rvApps.setVisibility(View.VISIBLE);
                    int withVal = 0;
                    for (AppItem i : finalItems) if (i.hasIdentity) withVal++;
                    if (LANG_ZH.equals(currentLang)) {
                        tvCount.setText(finalItems.size() + " 个应用 · " + withVal + " 个有伪装值");
                    } else if (LANG_EN.equals(currentLang)) {
                        tvCount.setText(finalItems.size() + " apps · " + withVal + " with identity");
                    } else {
                        tvCount.setText(finalItems.size() + " приложений · " + withVal + " с подменой");
                    }
                }
                // 写入诊断日志到 Download 文件夹
                writeDiagToDownload();
            });
        }).start();
    }

    /** 将诊断日志写入 /sdcard/Download/devicereset_diag.txt */
    private void writeDiagToDownload() {
        new Thread(() -> {
            try {
                String logContent = debugLog.toString();
                File tmpFile = new File(getCacheDir(), "devicereset_diag.txt");
                java.nio.file.Files.write(tmpFile.toPath(), logContent.getBytes(StandardCharsets.UTF_8));
                Process su = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                os.writeBytes("mkdir -p /sdcard/Download\n");
                os.writeBytes("cp '" + tmpFile.getAbsolutePath() + "' /sdcard/Download/devicereset_diag.txt\n");
                os.writeBytes("chmod 666 /sdcard/Download/devicereset_diag.txt\n");
                os.writeBytes("ls -l /sdcard/Download/devicereset_diag.txt\n");
                os.writeBytes("exit\n");
                os.flush();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(su.getInputStream()));
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) out.append(line);
                reader.close();
                su.waitFor();
                log("诊断日志已写入Download: " + out);
                tmpFile.delete();
            } catch (Throwable e) {
                log("写入诊断日志失败: " + e.getMessage());
            }
        }).start();
    }

    // ==================== LSPosed 作用域读取（多策略） ====================

    private Set<String> readLSPosedScope() {
        Set<String> result = new HashSet<>();
        List<String> dbPaths = findLSPosedDbPaths();
        log("找到配置文件: " + dbPaths);

        for (String path : dbPaths) {
            // 策略1: sqlite3 CLI
            Set<String> r1 = readScopeViaSqlite3(path);
            if (!r1.isEmpty()) {
                log("[sqlite3] 成功从 " + path + " 读取: " + r1);
                result.addAll(r1);
                break;
            }
            // 策略2: SQLiteDatabase（复制db+wal+shm）
            Set<String> r2 = readScopeViaSQLite(path);
            if (!r2.isEmpty()) {
                log("[SQLite] 成功从 " + path + " 读取: " + r2);
                result.addAll(r2);
                break;
            }
            // 策略3: 原始字节扫描（db + wal）
            Set<String> r3 = readScopeViaRaw(path);
            if (!r3.isEmpty()) {
                log("[Raw] 成功从 " + path + " 读取: " + r3);
                result.addAll(r3);
                break;
            }
        }
        result.remove(MODULE_PKG);
        return result;
    }

    /** 策略1: sqlite3 命令行直接查询 */
    private Set<String> readScopeViaSqlite3(String dbPath) {
        Set<String> result = new HashSet<>();
        try {
            // scope 表（每行一个应用）和 modules 表（scope 为 JSON 数组）两种格式都试
            String[] cmds = {
                    "sqlite3 '" + dbPath + "' \"SELECT app_pkg_name FROM scope WHERE module_pkg_name='" + MODULE_PKG + "'\"",
                    "sqlite3 '" + dbPath + "' SELECT app_pkg_name FROM scope WHERE module_pkg_name LIKE '%" + MODULE_PKG_SHORT + "%'",
                    "sqlite3 '" + dbPath + "' \"SELECT scope FROM modules WHERE module_pkg_name='" + MODULE_PKG + "'\"",
            };
            for (String cmd : cmds) {
                Process su = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                os.writeBytes(cmd + "\n");
                os.writeBytes("exit\n");
                os.flush();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(su.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("[")) {
                        try {
                            JSONArray arr = new JSONArray(line);
                            for (int i = 0; i < arr.length(); i++) {
                                String pkg = arr.getString(i);
                                if (isValidPackageName(pkg)) result.add(pkg);
                            }
                        } catch (Throwable ignored) {}
                    } else if (isValidPackageName(line)) {
                        result.add(line);
                    }
                }
                reader.close();
                su.waitFor();
                if (!result.isEmpty()) {
                    log("[sqlite3] 成功: " + result);
                    return result;
                }
            }
        } catch (Throwable e) {
            log("[sqlite3] 失败: " + e.getMessage());
        }
        return result;
    }

    /** 策略2: 复制数据库（含WAL），SQLiteDatabase 查询 */
    private Set<String> readScopeViaSQLite(String dbPath) {
        Set<String> result = new HashSet<>();
        String tmpBase = "/data/local/tmp/drs_lspd_db";
        try {
            // 用 dd 复制到 /data/local/tmp/（cat > 重定向在 su shell 中不可靠）
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("dd if='" + dbPath + "' of='" + tmpBase + "' bs=65536 2>/dev/null\n");
            os.writeBytes("dd if='" + dbPath + "-wal' of='" + tmpBase + "-wal' bs=65536 2>/dev/null\n");
            os.writeBytes("dd if='" + dbPath + "-shm' of='" + tmpBase + "-shm' bs=65536 2>/dev/null\n");
            os.writeBytes("chmod 666 '" + tmpBase + "' '" + tmpBase + "-wal' '" + tmpBase + "-shm' 2>/dev/null\n");
            os.writeBytes("ls -l '" + tmpBase + "'\n");
            os.writeBytes("exit\n");
            os.flush();
            // 读取 ls 输出确认文件大小
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(su.getInputStream()));
            StringBuilder lsOut = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) lsOut.append(line);
            reader.close();
            su.waitFor();
            log("[SQLite] 复制结果: " + lsOut);

            File tmpDb = new File(tmpBase);
            if (!tmpDb.exists() || tmpDb.length() < 100) {
                log("[SQLite] 文件不存在或太小: " + tmpDb.length());
                return result;
            }

            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    tmpBase, null, SQLiteDatabase.OPEN_READONLY);

            // 列出所有表
            Cursor tc = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            List<String> tables = new ArrayList<>();
            while (tc.moveToNext()) tables.add(tc.getString(0));
            tc.close();
            log("[SQLite] 表: " + tables);

            for (String table : tables) {
                try {
                    Cursor cc = db.rawQuery("PRAGMA table_info(" + table + ")", null);
                    String modulePkgCol = null, appPkgCol = null, scopeCol = null;
                    List<String> cols = new ArrayList<>();
                    while (cc.moveToNext()) {
                        String cn = cc.getString(1);
                        cols.add(cn);
                        String cnl = cn.toLowerCase();
                        if (cnl.equals("module_pkg_name") || cnl.equals("module_pkg")) modulePkgCol = cn;
                        if (cnl.equals("app_pkg_name") || cnl.equals("app_pkg")) appPkgCol = cn;
                        if (cnl.contains("scope") && !cnl.contains("blocked")) scopeCol = cn;
                    }
                    cc.close();
                    log("[SQLite] 表 " + table + " 列: " + cols);

                    // 情况A: scope 表（module_pkg_name + app_pkg_name，每行一个应用）
                    if (modulePkgCol != null && appPkgCol != null) {
                        Cursor cursor = db.rawQuery(
                                "SELECT " + appPkgCol + " FROM " + table + " WHERE " + modulePkgCol + "=?",
                                new String[]{MODULE_PKG});
                        while (cursor.moveToNext()) {
                            String pkg = cursor.getString(0);
                            if (isValidPackageName(pkg)) result.add(pkg);
                        }
                        cursor.close();
                        if (!result.isEmpty()) {
                            log("[SQLite] 从 " + table + " 表读取: " + result);
                            db.close();
                            return result;
                        }
                        // 模糊匹配
                        cursor = db.rawQuery(
                                "SELECT " + appPkgCol + " FROM " + table + " WHERE " + modulePkgCol + " LIKE ?",
                                new String[]{"%" + MODULE_PKG_SHORT + "%"});
                        while (cursor.moveToNext()) {
                            String pkg = cursor.getString(0);
                            if (isValidPackageName(pkg)) result.add(pkg);
                        }
                        cursor.close();
                        if (!result.isEmpty()) {
                            log("[SQLite] 从 " + table + " 表(模糊)读取: " + result);
                            db.close();
                            return result;
                        }
                    }

                    // 情况B: modules 表（scope 列为 JSON 数组）
                    if (modulePkgCol != null && scopeCol != null) {
                        Cursor cursor = db.rawQuery(
                                "SELECT " + scopeCol + " FROM " + table + " WHERE " + modulePkgCol + "=?",
                                new String[]{MODULE_PKG});
                        while (cursor.moveToNext()) {
                            String scopeJson = cursor.getString(0);
                            if (scopeJson != null && scopeJson.startsWith("[")) {
                                JSONArray arr = new JSONArray(scopeJson);
                                for (int i = 0; i < arr.length(); i++) {
                                    String pkg = arr.getString(i);
                                    if (isValidPackageName(pkg)) result.add(pkg);
                                }
                            }
                        }
                        cursor.close();
                        if (!result.isEmpty()) {
                            log("[SQLite] 从 " + table + ".scope(JSON)读取: " + result);
                            db.close();
                            return result;
                        }
                    }
                } catch (Throwable e) {
                    log("[SQLite] 表 " + table + " 出错: " + e.getMessage());
                }
            }
            db.close();
        } catch (Throwable e) {
            log("[SQLite] 失败: " + e.getMessage());
        } finally {
            // 清理临时文件
            try {
                Process su = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                os.writeBytes("rm -f '" + tmpBase + "' '" + tmpBase + "-wal' '" + tmpBase + "-shm'\n");
                os.writeBytes("exit\n");
                os.flush();
                su.waitFor();
            } catch (Throwable ignored) {}
        }
        return result;
    }

    /** 策略3: 原始字节扫描（db + wal，模块名附近提取包名） */
    private Set<String> readScopeViaRaw(String dbPath) {
        Set<String> result = new HashSet<>();
        try {
            byte[] mainData = readFileViaRoot(dbPath);
            byte[] walData = readFileViaRoot(dbPath + "-wal");
            log("[Raw] 主文件: " + (mainData == null ? 0 : mainData.length)
                    + " wal: " + (walData == null ? 0 : walData.length));

            String combined = "";
            if (mainData != null) combined += new String(mainData, StandardCharsets.UTF_8);
            if (walData != null) combined += new String(walData, StandardCharsets.UTF_8);
            if (combined.isEmpty()) return result;

            // 搜索模块名
            int pkgIdx = combined.indexOf(MODULE_PKG);
            if (pkgIdx < 0) {
                pkgIdx = combined.indexOf(MODULE_PKG_SHORT);
                log("[Raw] 用模糊名位置=" + pkgIdx);
            } else {
                log("[Raw] 精确包名位置=" + pkgIdx);
            }
            if (pkgIdx < 0) return result;

            // 模块名前后 128KB 窗口
            int start = Math.max(0, pkgIdx - 131072);
            int end = Math.min(combined.length(), pkgIdx + 131072);
            String window = combined.substring(start, end);

            // 方式A: 找 JSON 数组（兼容旧格式）
            Matcher m = JSON_ARRAY_PATTERN.matcher(window);
            while (m.find()) {
                try {
                    JSONArray arr = new JSONArray("[" + m.group(1) + "]");
                    for (int i = 0; i < arr.length(); i++) {
                        String pkg = arr.optString(i, "");
                        if (isValidPackageName(pkg)) result.add(pkg);
                    }
                } catch (Throwable ignored) {}
            }

            // 方式B: 直接提取窗口内所有合法包名（scope 表每行一个包名）
            Pattern pkgPattern = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+){1,5}");
            Matcher pm = pkgPattern.matcher(window);
            while (pm.find()) {
                String pkg = pm.group();
                if (isValidPackageName(pkg)) result.add(pkg);
            }

            log("[Raw] 找到包名: " + result);
        } catch (Throwable e) {
            log("[Raw] 失败: " + e.getMessage());
        }
        return result;
    }

    /** 查找 LSPosed 配置数据库路径 */
    private List<String> findLSPosedDbPaths() {
        List<String> paths = new ArrayList<>();
        String[] known = {
                "/data/adb/lspd/config/db",
                "/data/adb/lspd/config/modules_config.db",
        };
        for (String p : known) paths.add(p);

        // 列出目录
        try {
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("ls -1 /data/adb/lspd/config/ 2>/dev/null\n");
            os.writeBytes("exit\n");
            os.flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(su.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.endsWith("-wal") && !line.endsWith("-shm")) {
                    String full = "/data/adb/lspd/config/" + line;
                    if (!paths.contains(full)) paths.add(full);
                }
            }
            reader.close();
            su.waitFor();
        } catch (Throwable ignored) {}
        return paths;
    }

    /** 通过 root 用 pm path 查找应用 APK 路径（适用于其他用户/工作profile的应用） */
    private String findApkPathViaRoot(String packageName) {
        try {
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("pm path " + packageName + " 2>/dev/null\n");
            os.writeBytes("exit\n");
            os.flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(su.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("package:")) {
                    String path = line.substring(8).trim();
                    reader.close();
                    su.waitFor();
                    return path;
                }
            }
            reader.close();
            su.waitFor();
        } catch (Throwable ignored) {}
        return null;
    }

    /** 通过 root 读取文件原始字节 */
    private byte[] readFileViaRoot(String path) {
        try {
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("cat '" + path + "' 2>/dev/null\n");
            os.writeBytes("exit\n");
            os.flush();
            InputStream is = su.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();
            su.waitFor();
            byte[] data = baos.toByteArray();
            return data.length > 0 ? data : null;
        } catch (Throwable ignored) {}
        return null;
    }

    /** 检查是否为合法 Android 包名 */
    private boolean isValidPackageName(String pkg) {
        if (pkg == null || pkg.length() < 3) return false;
        if (!pkg.contains(".")) return false;
        if (!pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) return false;
        if (pkg.equals("android") || pkg.startsWith("android.")) return false;
        return true;
    }

    // ==================== 哨兵文件 ====================

    private Set<String> scanIdentityPackages() {
        Set<String> result = new HashSet<>();
        try {
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            // 用for循环遍历所有用户目录（find会被SELinux阻止进入其他用户目录）
            os.writeBytes("for u in /data/user/*/; do\n");
            os.writeBytes("  for d in \"$u\"*/; do\n");
            os.writeBytes("    pkg=$(basename \"$d\")\n");
            os.writeBytes("    if [ -f \"$d/files/.identity_sentinel\" ]; then\n");
            os.writeBytes("      echo \"$pkg\"\n");
            os.writeBytes("    fi\n");
            os.writeBytes("  done\n");
            os.writeBytes("done\n");
            os.writeBytes("for d in /data/data/*/; do\n");
            os.writeBytes("  pkg=$(basename \"$d\")\n");
            os.writeBytes("  if [ -f \"$d/files/.identity_sentinel\" ]; then\n");
            os.writeBytes("    echo \"$pkg\"\n");
            os.writeBytes("  fi\n");
            os.writeBytes("done\n");
            os.writeBytes("exit\n");
            os.flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(su.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && isValidPackageName(line)) result.add(line);
            }
            reader.close();
            su.waitFor();
        } catch (Throwable e) {
            log("哨兵扫描异常: " + e.getMessage());
        }
        log("哨兵扫描结果: " + result);
        return result;
    }

    private String readIdentityFile(String packageName) {
        String tmpPath = "/data/local/tmp/drs_read_" + System.currentTimeMillis();
        try {
            // 一个shell脚本完成：遍历所有用户 → 检查目录 → 检查文件 → 复制 → 输出诊断
            StringBuilder script = new StringBuilder();
            script.append("echo '=== 用户列表 ==='\n");
            script.append("ls -1 /data/user/ 2>/dev/null\n");
            script.append("echo '=== 搜索哨兵文件 ==='\n");
            script.append("FOUND=''\n");
            script.append("for u in /data/user/*/; do\n");
            script.append("  f=\"${u}").append(packageName).append("/files/.identity_sentinel\"\n");
            script.append("  if [ -f \"$f\" ]; then\n");
            script.append("    echo \"FOUND: $f ($(stat -c%s \"$f\" 2>/dev/null || echo unknown) bytes)\"\n");
            script.append("    head -c 200 \"$f\"\n");
            script.append("    echo ''\n");
            script.append("    dd if=\"$f\" of='").append(tmpPath).append("' bs=65536 2>/dev/null\n");
            script.append("    chmod 666 '").append(tmpPath).append("' 2>/dev/null\n");
            script.append("    FOUND=\"$f\"\n");
            script.append("  else\n");
            script.append("    echo \"MISSING: $f\"\n");
            script.append("    if [ -d \"${u}").append(packageName).append("\" ]; then\n");
            script.append("      echo \"  数据目录存在，files内容: $(ls -la \"${u}").append(packageName).append("/files/\" 2>&1 | tr '\\n' ' ')\"\n");
            script.append("    else\n");
            script.append("      echo \"  数据目录不存在\"\n");
            script.append("    fi\n");
            script.append("  fi\n");
            script.append("done\n");
            // 也检查 /data/data/
            script.append("f2='/data/data/").append(packageName).append("/files/.identity_sentinel'\n");
            script.append("if [ -f \"$f2\" ] && [ -z \"$FOUND\" ]; then\n");
            script.append("  echo \"FOUND: $f2\"\n");
            script.append("  dd if=\"$f2\" of='").append(tmpPath).append("' bs=65536 2>/dev/null\n");
            script.append("  chmod 666 '").append(tmpPath).append("' 2>/dev/null\n");
            script.append("fi\n");
            script.append("echo '=== 完成 ==='\n");
            script.append("exit\n");

            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes(script.toString());
            os.flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(su.getInputStream()));
            StringBuilder diag = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                diag.append(line).append("\n");
            }
            reader.close();
            su.waitFor();
            log("读取诊断 " + packageName + ":\n" + diag);

            File tmpFile = new File(tmpPath);
            if (tmpFile.exists() && tmpFile.length() > 0) {
                byte[] data = java.nio.file.Files.readAllBytes(tmpFile.toPath());
                String output = new String(data, StandardCharsets.UTF_8).trim();
                tmpFile.delete();
                if (!output.isEmpty() && output.startsWith("{")) {
                    log("成功读取伪装值 " + packageName + " (" + output.length() + " bytes)");
                    return output;
                } else {
                    log("文件内容不是JSON: " + output.substring(0, Math.min(100, output.length())));
                }
            } else {
                log("临时文件不存在或为空: " + tmpPath);
            }
        } catch (Throwable e) {
            log("读取异常 " + packageName + ": " + e.getMessage());
        }
        log("未读取到伪装值 " + packageName);
        return null;
    }

    /** 生成随机身份并写入目标应用的哨兵文件 */
    private void generateAndWriteIdentity(AppItem item) {
        new Thread(() -> {
            try {
                Identity identity = IdentityGenerator.generateRandom();
                String json = identity.toJson();
                boolean ok = writeIdentityFile(item.packageName, json);
                if (ok) {
                    item.identityJson = json;
                    item.hasIdentity = true;
                    runOnUiThread(() -> {
                        adapter.notifyDataSetChanged();
                        showIdentityDialog(item);
                    });
                } else {
                    runOnUiThread(() -> {
                        String title, msg, btnOk;
                        if (LANG_ZH.equals(currentLang)) {
                            title = "写入失败"; msg = "无法写入伪装值到目标应用目录。\n请确保已授予 Root 权限。"; btnOk = "确定";
                        } else if (LANG_EN.equals(currentLang)) {
                            title = "Write Failed"; msg = "Cannot write identity to target app directory.\nPlease grant Root permission."; btnOk = "OK";
                        } else {
                            title = "Ошибка записи"; msg = "Не удалось записать подмену в каталог приложения.\nПредоставьте права Root."; btnOk = "ОК";
                        }
                        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(btnOk, null).show();
                    });
                }
            } catch (Throwable e) {
                log("生成身份异常: " + e.getMessage());
            }
        }).start();
    }

    /** 通过 root 将身份 JSON 写入目标应用的哨兵文件（所有用户目录） */
    private boolean writeIdentityFile(String packageName, String json) {
        // 先找到目标应用在所有用户下的 files 目录
        List<String> dirs = findAppFilesDirs(packageName);
        log("写入目标目录 " + packageName + ": " + dirs);
        if (dirs.isEmpty()) {
            log("未找到目标应用的任何数据目录，写入失败");
            return false;
        }

        String tmpPath = null;
        try {
            File tmpFile = new File(getCacheDir(), "drs_write_" + System.currentTimeMillis() + ".json");
            java.nio.file.Files.write(tmpFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
            tmpPath = tmpFile.getAbsolutePath();

            boolean anySuccess = false;
            for (String dir : dirs) {
                try {
                    Process su = Runtime.getRuntime().exec("su");
                    java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                    os.writeBytes("mkdir -p '" + dir + "'\n");
                    os.writeBytes("cp '" + tmpPath + "' '" + dir + "/.identity_sentinel'\n");
                    os.writeBytes("chmod 666 '" + dir + "/.identity_sentinel'\n");
                    os.writeBytes("if [ -f '" + dir + "/.identity_sentinel' ]; then echo 'OK:'$(wc -c < '" + dir + "/.identity_sentinel'); else echo 'FAIL'; fi\n");
                    os.writeBytes("exit\n");
                    os.flush();
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(su.getInputStream()));
                    StringBuilder out = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) out.append(line);
                    reader.close();
                    su.waitFor();
                    log("写入结果 " + dir + ": " + out);
                    if (out.toString().startsWith("OK:")) anySuccess = true;
                } catch (Throwable e) {
                    log("写入异常 " + dir + ": " + e.getMessage());
                }
            }
            if (tmpPath != null) new File(tmpPath).delete();
            if (anySuccess) {
                // 验证读取
                String verify = readIdentityFile(packageName);
                return verify != null;
            }
        } catch (Throwable e) {
            log("写入身份异常: " + e.getMessage());
        }
        return false;
    }

    /** 用 for 循环遍历所有用户目录，定位目标应用的数据目录，返回 files 目录路径（自动创建） */
    private List<String> findAppFilesDirs(String packageName) {
        List<String> dataDirs = new ArrayList<>();
        try {
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            // 用for循环遍历（find会被SELinux阻止进入其他用户目录）
            os.writeBytes("for u in /data/user/*/; do\n");
            os.writeBytes("  d=\"$u" + packageName + "\"\n");
            os.writeBytes("  if [ -d \"$d\" ]; then echo \"$d\"; fi\n");
            os.writeBytes("done\n");
            os.writeBytes("d='/data/data/" + packageName + "'\n");
            os.writeBytes("if [ -d \"$d\" ]; then echo \"$d\"; fi\n");
            os.writeBytes("exit\n");
            os.flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(su.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !dataDirs.contains(line)) {
                    dataDirs.add(line);
                }
            }
            reader.close();
            su.waitFor();
        } catch (Throwable e) {
            log("find数据目录失败 " + packageName + ": " + e.getMessage());
        }
        // 兜底：常见用户ID
        if (dataDirs.isEmpty()) {
            for (int uid : new int[]{0, 10, 11, 12, 13}) {
                String candidate = "/data/user/" + uid + "/" + packageName;
                if (!dataDirs.contains(candidate)) dataDirs.add(candidate);
            }
            dataDirs.add("/data/data/" + packageName);
        }
        log("找到数据目录 " + packageName + ": " + dataDirs);
        List<String> filesDirs = new ArrayList<>();
        for (String d : dataDirs) {
            filesDirs.add(d + "/files");
        }
        return filesDirs;
    }

    private void showIdentityDialog(AppItem item) {
        boolean zh = LANG_ZH.equals(currentLang);
        boolean en = LANG_EN.equals(currentLang);
        // 始终尝试读取，不依赖预扫描结果
        String json = item.identityJson;
        if (json == null) {
            json = readIdentityFile(item.packageName);
            item.identityJson = json;
            if (json != null) item.hasIdentity = true;
        }
        if (json == null) {
            String msg, btnGen, btnCancel;
            if (zh) {
                msg = "该应用暂无伪装值。\n可以立即生成一套随机伪装身份并写入，\n目标应用下次启动时将使用此身份。";
                btnGen = "生成伪装值"; btnCancel = "取消";
            } else if (en) {
                msg = "No identity for this app yet.\nGenerate a random identity and write it now.\nThe target app will use it on next launch.";
                btnGen = "Generate"; btnCancel = "Cancel";
            } else {
                msg = "Подмена для этого приложения ещё не задана.\nМожно сгенерировать случайную подмену и записать.\nПриложение использует её при следующем запуске.";
                btnGen = "Сгенерировать"; btnCancel = "Отмена";
            }
            new AlertDialog.Builder(this)
                    .setTitle(item.appName)
                    .setMessage(msg)
                    .setPositiveButton(btnGen, (d, w) -> generateAndWriteIdentity(item))
                    .setNegativeButton(btnCancel, null)
                    .show();
            return;
        }
        Identity id = Identity.fromJson(item.identityJson);
        if (id == null) {
            String title = zh ? "解析失败" : en ? "Parse Error" : "Ошибка парсинга";
            String ok = zh ? "确定" : en ? "OK" : "ОК";
            new AlertDialog.Builder(this).setTitle(title).setPositiveButton(ok, null).show();
            return;
        }
        String lApp = zh ? "应用" : en ? "App" : "Приложение";
        String lPkg = zh ? "包名" : en ? "Package" : "Пакет";
        String lAdId = zh ? "广告ID" : en ? "Ad ID" : "Рекл. ID";
        String lSerial = zh ? "序列号" : en ? "Serial" : "Серийный";
        String lBrand = zh ? "品牌" : en ? "Brand" : "Бренд";
        String lModel = zh ? "型号" : en ? "Model" : "Модель";
        String lMfr = zh ? "厂商" : en ? "Manufacturer" : "Производитель";
        String lFp = zh ? "指纹" : en ? "Fingerprint" : "Отпечаток";
        String lCarrier = zh ? "运营商" : en ? "Carrier" : "Оператор";
        String lCarrierCode = zh ? "运营商代码" : en ? "Carrier Code" : "Код оператора";
        String lTitle = zh ? "当前伪装值（伪装 → 原始）" : en ? "Current Identity (spoofed → original)" : "Текущая подмена (подмена → оригинал)";
        String ok = zh ? "确定" : en ? "OK" : "ОК";
        String lOriginal = zh ? "原始" : en ? "orig" : "ориг";

        // 获取设备原始值
        String origAndroidId = "";
        String origBrand = android.os.Build.BRAND;
        String origModel = android.os.Build.MODEL;
        String origMfr = android.os.Build.MANUFACTURER;
        String origFp = android.os.Build.FINGERPRINT;
        String origSerial = android.os.Build.SERIAL;
        String origMac = "";
        String origCarrier = "";
        String origCarrierCode = "";
        try {
            origAndroidId = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {}
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) origMac = wm.getConnectionInfo().getMacAddress();
        } catch (Throwable ignored) {}
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) {
                origCarrier = tm.getNetworkOperatorName();
                origCarrierCode = tm.getNetworkOperator();
            }
        } catch (Throwable ignored) {}

        StringBuilder sb = new StringBuilder();
        sb.append(lApp).append(": ").append(item.appName).append("\n");
        sb.append(lPkg).append(": ").append(item.packageName).append("\n\n");
        appendCompare(sb, "Android ID", id.androidId, origAndroidId);
        appendCompare(sb, lAdId, id.advertisingId, "—");
        if (id.appSetId != null) appendCompare(sb, "AppSet ID", id.appSetId, "—");
        appendCompare(sb, "IMEI", id.imei, "—");
        if (id.meid != null) appendCompare(sb, "MEID", id.meid, "—");
        appendCompare(sb, lSerial, id.serial, origSerial);
        appendCompare(sb, "MAC", id.macAddress, origMac);
        if (id.gsfId != null) appendCompare(sb, "GSF ID", id.gsfId, "—");
        sb.append("\n");
        appendCompare(sb, lBrand, id.brand, origBrand);
        appendCompare(sb, lModel, id.model, origModel);
        appendCompare(sb, lMfr, id.manufacturer, origMfr);
        appendCompare(sb, lFp, id.fingerprint, origFp);
        if (id.buildId != null) appendCompare(sb, "Build ID", id.buildId, android.os.Build.ID);
        sb.append("\n");
        appendCompare(sb, lCarrier, id.networkOperatorName, origCarrier);
        appendCompare(sb, lCarrierCode, id.networkOperator, origCarrierCode);

        new AlertDialog.Builder(this)
                .setTitle(lTitle)
                .setMessage(sb.toString())
                .setPositiveButton(ok, null)
                .show();
    }

    /** 对比身份值与原机真实值，关键字段不同则认为已伪装 */
    private boolean isIdentitySpoofed(Identity id) {
        if (id == null) return false;
        // Android ID 对比
        try {
            String origAndroidId = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (id.androidId != null && !id.androidId.equals(origAndroidId)) return true;
        } catch (Throwable ignored) {}
        // 品牌对比
        if (id.brand != null && !id.brand.equalsIgnoreCase(android.os.Build.BRAND)) return true;
        // 型号对比
        if (id.model != null && !id.model.equals(android.os.Build.MODEL)) return true;
        // 厂商对比
        if (id.manufacturer != null && !id.manufacturer.equalsIgnoreCase(android.os.Build.MANUFACTURER)) return true;
        // 指纹对比
        if (id.fingerprint != null && !id.fingerprint.equals(android.os.Build.FINGERPRINT)) return true;
        // IMEI 对比（有值且非空通常就是伪装的）
        if (id.imei != null && !id.imei.isEmpty()) return true;
        // 只要有任意关键字段不同就返回true
        return false;
    }

    /** 追加一行对比：伪装值 → 原始值，不同则标记 */
    private void appendCompare(StringBuilder sb, String label, String spoofed, String original) {
        if (spoofed == null) return;
        sb.append(label).append(": ").append(spoofed);
        if (original != null && !original.isEmpty() && !original.equals("—")) {
            if (!spoofed.equals(original)) {
                sb.append("  →  ").append(original).append(" ✗");
            } else {
                sb.append("  ✓");
            }
        }
        sb.append("\n");
    }

    // ==================== Adapter ====================
    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app_picker, parent, false);
            float density = parent.getResources().getDisplayMetrics().density;
            GlassButtonDrawable bg = new GlassButtonDrawable(
                    Math.round(16 * density), Math.round(1 * density), false);
            v.setBackground(bg);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AppItem item = appList.get(position);
            holder.icon.setImageDrawable(item.icon);
            holder.name.setText(item.appName);
            holder.pkg.setText(item.packageName);
            if (item.hasIdentity) {
                holder.dot.setBackgroundResource(R.drawable.status_dot);
            } else {
                GradientDrawable grayDot = new GradientDrawable();
                grayDot.setShape(GradientDrawable.OVAL);
                grayDot.setColor(0xFF444444);
                holder.dot.setBackground(grayDot);
            }
            holder.itemView.setOnClickListener(v -> showIdentityDialog(item));
        }

        @Override
        public int getItemCount() {
            return appList.size();
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView name;
            TextView pkg;
            View dot;
            VH(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.app_icon);
                name = itemView.findViewById(R.id.app_name);
                pkg = itemView.findViewById(R.id.app_package);
                dot = itemView.findViewById(R.id.status_dot);
            }
        }
    }
}
