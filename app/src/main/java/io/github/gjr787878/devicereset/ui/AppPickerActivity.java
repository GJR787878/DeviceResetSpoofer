package io.github.gjr787878.devicereset.ui;

import android.app.AlertDialog;
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

public class AppPickerActivity extends AppCompatActivity {

    private static final String MODULE_PKG = "io.github.gjr787878.devicereset";
    private static final String MODULE_PKG_SHORT = "devicereset";
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[([^\\[\\]]{2,5000})\\]");

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
        setContentView(R.layout.activity_app_picker);

        tvLoading = findViewById(R.id.tv_loading);
        tvEmpty = findViewById(R.id.tv_empty);
        tvCount = findViewById(R.id.tv_count);
        tvDebug = findViewById(R.id.tv_debug);
        rvApps = findViewById(R.id.rv_apps);
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        rvApps.setAdapter(adapter);

        setTitle("选择应用");
        loadApps();
    }

    private void log(String msg) {
        debugLog.append(msg).append("\n");
    }

    private void loadApps() {
        debugLog.setLength(0);
        new Thread(() -> {
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
            List<AppItem> items = new ArrayList<>();
            for (String pkg : allPkgs) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    AppItem item = new AppItem();
                    item.packageName = pkg;
                    item.appName = pm.getApplicationLabel(ai).toString();
                    item.icon = pm.getApplicationIcon(ai);
                    item.hasIdentity = identityPkgs.contains(pkg);
                    if (item.hasIdentity) {
                        item.identityJson = readIdentityFile(pkg);
                    }
                    items.add(item);
                } catch (Throwable ignored) {}
            }

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
                    tvEmpty.setText("未找到作用域应用。\n请在 LSPosed 中勾选目标应用并重启。");
                    tvDebug.setText(debugLog.toString());
                    tvDebug.setVisibility(View.VISIBLE);
                    rvApps.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    tvDebug.setVisibility(View.GONE);
                    rvApps.setVisibility(View.VISIBLE);
                    int withVal = 0;
                    for (AppItem i : finalItems) if (i.hasIdentity) withVal++;
                    tvCount.setText(finalItems.size() + " 个应用 · " + withVal + " 个有伪装值");
                }
            });
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
            String[] cmds = {
                    "sqlite3 '" + dbPath + "' \"SELECT scope FROM modules WHERE module_pkg_name='" + MODULE_PKG + "'\"",
                    "sqlite3 '" + dbPath + "' \"SELECT scope FROM modules WHERE module_pkg_name LIKE '%" + MODULE_PKG_SHORT + "%'\"",
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
                    if (line.startsWith("[")) {
                        try {
                            JSONArray arr = new JSONArray(line);
                            for (int i = 0; i < arr.length(); i++) {
                                String pkg = arr.getString(i);
                                if (isValidPackageName(pkg)) result.add(pkg);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                reader.close();
                su.waitFor();
                if (!result.isEmpty()) return result;
            }
        } catch (Throwable e) {
            log("[sqlite3] 失败: " + e.getMessage());
        }
        return result;
    }

    /** 策略2: 复制数据库（含WAL），SQLiteDatabase 查询 */
    private Set<String> readScopeViaSQLite(String dbPath) {
        Set<String> result = new HashSet<>();
        File tmpDb = null;
        try {
            String base = getCacheDir().getAbsolutePath() + "/lspd_db";
            tmpDb = new File(base);
            // 复制主文件 + wal + shm
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("cat '" + dbPath + "' > '" + base + "'\n");
            os.writeBytes("cat '" + dbPath + "-wal' > '" + base + "-wal' 2>/dev/null\n");
            os.writeBytes("cat '" + dbPath + "-shm' > '" + base + "-shm' 2>/dev/null\n");
            os.writeBytes("chmod 666 '" + base + "' '" + base + "-wal' '" + base + "-shm' 2>/dev/null\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();

            if (!tmpDb.exists() || tmpDb.length() < 100) {
                log("[SQLite] 文件复制失败或太小: " + tmpDb.length());
                return result;
            }

            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    base, null, SQLiteDatabase.OPEN_READONLY);

            // 列出所有表
            Cursor tc = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            List<String> tables = new ArrayList<>();
            while (tc.moveToNext()) tables.add(tc.getString(0));
            tc.close();
            log("[SQLite] 表: " + tables);

            for (String table : tables) {
                try {
                    Cursor cc = db.rawQuery("PRAGMA table_info(" + table + ")", null);
                    String pkgCol = null, scopeCol = null;
                    List<String> cols = new ArrayList<>();
                    while (cc.moveToNext()) {
                        String cn = cc.getString(1);
                        cols.add(cn);
                        String cnl = cn.toLowerCase();
                        if (cnl.contains("pkg") || cnl.contains("package")) pkgCol = cn;
                        if (cnl.contains("scope")) scopeCol = cn;
                    }
                    cc.close();
                    log("[SQLite] 表 " + table + " 列: " + cols + " pkgCol=" + pkgCol + " scopeCol=" + scopeCol);

                    if (pkgCol != null && scopeCol != null) {
                        // 先精确匹配，再模糊匹配
                        String[] queries = {
                                "SELECT " + scopeCol + " FROM " + table + " WHERE " + pkgCol + "=?",
                                "SELECT " + scopeCol + " FROM " + table + " WHERE " + pkgCol + " LIKE ?",
                        };
                        String[][] args = {
                                {MODULE_PKG},
                                {"%" + MODULE_PKG_SHORT + "%"},
                        };
                        for (int qi = 0; qi < queries.length; qi++) {
                            Cursor cursor = db.rawQuery(queries[qi], args[qi]);
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
                                db.close();
                                return result;
                            }
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
            if (tmpDb != null) {
                tmpDb.delete();
                new File(tmpDb.getAbsolutePath() + "-wal").delete();
                new File(tmpDb.getAbsolutePath() + "-shm").delete();
            }
        }
        return result;
    }

    /** 策略3: 原始字节扫描（db + wal，全文件搜索模块名附近的JSON数组） */
    private Set<String> readScopeViaRaw(String dbPath) {
        Set<String> result = new HashSet<>();
        try {
            // 读取主文件和 wal 文件，合并
            byte[] mainData = readFileViaRoot(dbPath);
            byte[] walData = readFileViaRoot(dbPath + "-wal");
            log("[Raw] 主文件大小: " + (mainData == null ? 0 : mainData.length)
                    + " wal大小: " + (walData == null ? 0 : walData.length));

            String combined = "";
            if (mainData != null) combined += new String(mainData, StandardCharsets.UTF_8);
            if (walData != null) combined += new String(walData, StandardCharsets.UTF_8);
            if (combined.isEmpty()) return result;

            // 搜索模块名（精确和模糊）
            int pkgIdx = combined.indexOf(MODULE_PKG);
            if (pkgIdx < 0) {
                pkgIdx = combined.indexOf(MODULE_PKG_SHORT);
                log("[Raw] 精确包名未找到，用模糊名 '" + MODULE_PKG_SHORT + "' 位置=" + pkgIdx);
            } else {
                log("[Raw] 找到精确包名，位置=" + pkgIdx);
            }
            if (pkgIdx < 0) return result;

            // 在模块名前后各 64KB 范围内找 JSON 数组
            int start = Math.max(0, pkgIdx - 65536);
            int end = Math.min(combined.length(), pkgIdx + 65536);
            String window = combined.substring(start, end);
            log("[Raw] 搜索窗口大小: " + window.length());

            Matcher m = JSON_ARRAY_PATTERN.matcher(window);
            while (m.find()) {
                String jsonStr = "[" + m.group(1) + "]";
                try {
                    JSONArray arr = new JSONArray(jsonStr);
                    if (arr.length() == 0) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        String pkg = arr.optString(i, "");
                        if (isValidPackageName(pkg)) result.add(pkg);
                    }
                } catch (Throwable ignored) {}
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
            os.writeBytes("for d in /data/data/*/; do pkg=$(basename \"$d\"); if [ -f \"$d/files/.identity_sentinel\" ]; then echo \"$pkg\"; fi; done\n");
            os.writeBytes("exit\n");
            os.flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(su.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) result.add(line);
            }
            reader.close();
            su.waitFor();
        } catch (Throwable ignored) {}
        return result;
    }

    private String readIdentityFile(String packageName) {
        try {
            String path = "/data/data/" + packageName + "/files/.identity_sentinel";
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("cat '" + path + "'\n");
            os.writeBytes("exit\n");
            os.flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(su.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            su.waitFor();
            String output = sb.toString().trim();
            if (!output.isEmpty() && output.startsWith("{")) return output;
        } catch (Throwable ignored) {}
        return null;
    }

    private void showIdentityDialog(AppItem item) {
        if (!item.hasIdentity || item.identityJson == null) {
            new AlertDialog.Builder(this)
                    .setTitle(item.appName)
                    .setMessage("该应用暂无伪装值。\n请先运行一次该应用，模块会自动生成伪装身份。")
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }
        Identity id = Identity.fromJson(item.identityJson);
        if (id == null) {
            new AlertDialog.Builder(this).setTitle("解析失败").setPositiveButton("确定", null).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("应用: ").append(item.appName).append("\n");
        sb.append("包名: ").append(item.packageName).append("\n\n");
        if (id.androidId != null) sb.append("Android ID: ").append(id.androidId).append("\n");
        if (id.advertisingId != null) sb.append("广告ID: ").append(id.advertisingId).append("\n");
        if (id.appSetId != null) sb.append("AppSet ID: ").append(id.appSetId).append("\n");
        if (id.imei != null) sb.append("IMEI: ").append(id.imei).append("\n");
        if (id.meid != null) sb.append("MEID: ").append(id.meid).append("\n");
        if (id.serial != null) sb.append("序列号: ").append(id.serial).append("\n");
        if (id.macAddress != null) sb.append("MAC: ").append(id.macAddress).append("\n");
        if (id.gsfId != null) sb.append("GSF ID: ").append(id.gsfId).append("\n");
        sb.append("\n");
        if (id.brand != null) sb.append("品牌: ").append(id.brand).append("\n");
        if (id.model != null) sb.append("型号: ").append(id.model).append("\n");
        if (id.manufacturer != null) sb.append("厂商: ").append(id.manufacturer).append("\n");
        if (id.fingerprint != null) sb.append("指纹: ").append(id.fingerprint).append("\n");
        if (id.buildId != null) sb.append("Build ID: ").append(id.buildId).append("\n");
        sb.append("\n");
        if (id.networkOperatorName != null) sb.append("运营商: ").append(id.networkOperatorName).append("\n");
        if (id.networkOperator != null) sb.append("运营商代码: ").append(id.networkOperator).append("\n");

        new AlertDialog.Builder(this)
                .setTitle("当前伪装值")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .show();
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
