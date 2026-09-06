package io.github.gjr787878.devicereset.ui;

import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
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

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.gjr787878.devicereset.GlassButtonDrawable;
import io.github.gjr787878.devicereset.R;
import io.github.gjr787878.devicereset.xposed.Identity;

public class AppPickerActivity extends AppCompatActivity {

    private static final String LSPD_DB_PATH = "/data/adb/lspd/config/db";
    private static final String MODULE_PKG = "io.github.gjr787878.devicereset";

    private RecyclerView rvApps;
    private TextView tvLoading;
    private TextView tvEmpty;
    private TextView tvCount;
    private AppAdapter adapter;
    private final List<AppItem> appList = new ArrayList<>();

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
        rvApps = findViewById(R.id.rv_apps);
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        rvApps.setAdapter(adapter);

        setTitle("选择应用");
        loadApps();
    }

    private void loadApps() {
        new Thread(() -> {
            // 1. 从 LSPosed 配置数据库读取作用域
            Set<String> scopePkgs = readLSPosedScope();
            // 2. 扫描有哨兵文件的应用（有伪装值）
            Set<String> identityPkgs = scanIdentityPackages();

            // 合并：作用域中的应用 + 有伪装值的应用
            Set<String> allPkgs = new HashSet<>();
            allPkgs.addAll(scopePkgs);
            allPkgs.addAll(identityPkgs);

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

            // 排序：有伪装值的排前面
            items.sort((a, b) -> {
                if (a.hasIdentity != b.hasIdentity) return a.hasIdentity ? -1 : 1;
                return a.appName.compareToIgnoreCase(b.appName);
            });

            runOnUiThread(() -> {
                tvLoading.setVisibility(View.GONE);
                appList.clear();
                appList.addAll(items);
                adapter.notifyDataSetChanged();
                if (items.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("未找到作用域应用。\n请在 LSPosed 中勾选目标应用并重启。");
                    rvApps.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    rvApps.setVisibility(View.VISIBLE);
                    int withVal = 0;
                    for (AppItem i : items) if (i.hasIdentity) withVal++;
                    tvCount.setText(items.size() + " 个应用 · " + withVal + " 个有伪装值");
                }
            });
        }).start();
    }

    /** 从 LSPosed 配置数据库读取模块作用域 */
    private Set<String> readLSPosedScope() {
        Set<String> result = new HashSet<>();
        File tmpDb = null;
        try {
            tmpDb = new File(getCacheDir(), "lspd_db");
            // 用 root 复制数据库到缓存目录
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("cp '" + LSPD_DB_PATH + "' '" + tmpDb.getAbsolutePath() + "'\n");
            os.writeBytes("chmod 666 '" + tmpDb.getAbsolutePath() + "'\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();

            if (!tmpDb.exists() || tmpDb.length() == 0) return result;

            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    tmpDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            // 查询 modules 表中本模块的 scope 字段
            Cursor cursor = db.rawQuery(
                    "SELECT scope FROM modules WHERE module_pkg_name=?",
                    new String[]{MODULE_PKG});
            if (cursor.moveToFirst()) {
                String scopeJson = cursor.getString(0);
                if (scopeJson != null) {
                    JSONArray arr = new JSONArray(scopeJson);
                    for (int i = 0; i < arr.length(); i++) {
                        result.add(arr.getString(i));
                    }
                }
            }
            cursor.close();
            db.close();
        } catch (Throwable ignored) {
        } finally {
            if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
        }
        return result;
    }

    /** 扫描所有有 .identity_sentinel 文件的应用 */
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

    /** 读取目标应用的哨兵文件 */
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
            // 玻璃卡片背景
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
            // 状态点颜色
            if (item.hasIdentity) {
                holder.dot.setBackgroundResource(R.drawable.status_dot);
            } else {
                holder.dot.setBackgroundColor(0xFF444444);
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
