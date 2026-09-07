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
          try {
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
                // 先读哨兵文件（文件是真相来源），读到后与本机真实值对比，不同才标蓝
                String json = readIdentityFileWithRetry(pkg);
                if (json != null && json.startsWith("{")) {
                    item.identityJson = json;
                    // 检测到与本机真实值不同 → 自动标蓝
                    item.hasIdentity = isActuallySpoofed(json);
                    // 同步到SharedPreferences
                    getSharedPreferences("devicereset_ui", MODE_PRIVATE).edit()
                            .putString("identity_" + pkg, json).apply();
                    log("从文件读取到伪装值 " + pkg + " (" + json.length() + " bytes) 与本机不同=" + item.hasIdentity);
                } else {
                    // 文件不存在或读取失败 → 清除旧的SharedPreferences缓存，显示无伪装值
                    getSharedPreferences("devicereset_ui", MODE_PRIVATE).edit()
                            .remove("identity_" + pkg).apply();
                    log("文件不存在，清除旧缓存 " + pkg);
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
                    // 点击计数文字触发重新扫描
                    tvCount.setOnClickListener(v -> {
                        debugLog.setLength(0);
                        log("手动重新扫描...");
                        new Thread(() -> {
                            rescanIdentitiesFromFiles();
                            runOnUiThread(this::updateCount);
                        }).start();
                    });
                }
                // 写入诊断日志到 Download 文件夹
                writeDiagToDownload();
            });
            // 进入后连续多轮自动重扫，模块一生成新身份就自动标蓝，无需手动生成
            startAutoRescanSeries();
          } catch (Throwable fatal) {
            // 兜底：任何异常都不能让界面永久停在"正在扫描"，并务必写出诊断日志
            log("加载致命异常: " + fatal);
            final String emsg = String.valueOf(fatal);
            runOnUiThread(() -> {
                tvLoading.setVisibility(View.GONE);
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText("扫描异常: " + emsg);
                tvDebug.setText(debugLog.toString());
                tvDebug.setVisibility(View.VISIBLE);
                rvApps.setVisibility(View.GONE);
            });
            writeDiagToDownload();
          }
        }).start();
    }

    /** 后台重新扫描所有应用的哨兵文件，发现新身份则更新，确认文件消失才清除 */
    private void rescanIdentitiesFromFiles() {
        boolean changed = false;
        for (AppItem item : appList) {
            String json = readIdentityFile(item.packageName);
            if (json != null && json.startsWith("{")) {
                // 文件存在，与当前值对比，不同则更新；并与本机真实值对比决定是否标蓝
                boolean spoofed = isActuallySpoofed(json);
                if (!json.equals(item.identityJson) || item.hasIdentity != spoofed) {
                    item.identityJson = json;
                    item.hasIdentity = spoofed;
                    getSharedPreferences("devicereset_ui", MODE_PRIVATE).edit()
                            .putString("identity_" + item.packageName, json).apply();
                    log("重扫发现身份 " + item.packageName + " (" + json.length() + " bytes) 与本机不同=" + spoofed);
                    changed = true;
                }
            } else if (json != null && json.isEmpty()) {
                // 明确确认所有路径都没有文件（清数据后）→ 才清除旧身份
                if (item.hasIdentity) {
                    item.identityJson = null;
                    item.hasIdentity = false;
                    getSharedPreferences("devicereset_ui", MODE_PRIVATE).edit()
                            .remove("identity_" + item.packageName).apply();
                    log("重扫确认文件已删除，清除身份 " + item.packageName);
                    changed = true;
                }
            }
            // json == null：读取超时/出错/不确定 → 保留当前状态，绝不清零（防止su抖动把已读到的值清掉）
        }
        if (changed) {
            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                updateCount();
            });
        }
        writeDiagToDownload();
    }

    /** 判断伪装JSON是否确实与本机真实值不同（关键字段任一不同即视为已伪装） */
    private boolean isActuallySpoofed(String json) {
        if (json == null || !json.startsWith("{")) return false;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            String realAndroidId = android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            String fakeAndroidId = o.optString("androidId", "");
            if (!fakeAndroidId.isEmpty() && !fakeAndroidId.equalsIgnoreCase(realAndroidId)) return true;
            if (!o.optString("brand", "").equalsIgnoreCase(android.os.Build.BRAND)) return true;
            if (!o.optString("model", "").equals(android.os.Build.MODEL)) return true;
            if (!o.optString("manufacturer", "").equalsIgnoreCase(android.os.Build.MANUFACTURER)) return true;
            if (!o.optString("fingerprint", "").equals(android.os.Build.FINGERPRINT)) return true;
            if (!o.optString("imei", "").isEmpty()) return true;
            if (!o.optString("serial", "").isEmpty()
                    && !o.optString("serial", "").equalsIgnoreCase(android.os.Build.SERIAL)) return true;
            // 关键字段全部和本机相同 = 实际没伪装
            log("对比真实值：伪装值与本机完全相同，不标蓝 " + fakeAndroidId);
            return false;
        } catch (Throwable e) {
            // 解析失败时，文件既然存在就保守认为有伪装值
            log("对比真实值解析异常，按有伪装处理: " + e.getMessage());
            return true;
        }
    }

    /**
     * 进入页面后连续多轮自动重扫：模块写文件可能稍晚于UI加载，
     * 只要在时间窗内读到与本机不同的伪装值，就自动标蓝，无需用户手动生成。
     * 所有作用域应用都已识别到伪装值后提前结束。
     */
    private void startAutoRescanSeries() {
        new Thread(() -> {
            long[] delaysMs = {800, 2000, 4000, 7000, 11000};
            for (long delay : delaysMs) {
                try { Thread.sleep(delay); } catch (Throwable ignored) {}
                if (isFinishing() || isDestroyed()) return;
                rescanIdentitiesFromFiles();
                runOnUiThread(this::updateCount);
                // 所有作用域应用都有伪装值了，停止轮询
                boolean allHave = !appList.isEmpty();
                for (AppItem it : appList) {
                    if (!it.hasIdentity) { allHave = false; break; }
                }
                if (allHave) {
                    log("所有应用均已识别伪装值，停止自动重扫");
                    return;
                }
            }
        }).start();
    }

    /** 更新顶部计数文字 */
    private void updateCount() {
        int total = appList.size();
        int withVal = 0;
        for (AppItem it : appList) if (it.hasIdentity) withVal++;
        if (LANG_ZH.equals(currentLang)) {
            tvCount.setText(total + " 个应用 · " + withVal + " 个有伪装值");
        } else if (LANG_EN.equals(currentLang)) {
            tvCount.setText(total + " apps · " + withVal + " with identity");
        } else {
            tvCount.setText(total + " приложений · " + withVal + " с подменой");
        }
    }

    /** 将诊断日志（含本机真实值+伪装值对比）写入 /sdcard/Download/111 */
    private void writeDiagToDownload() {
        new Thread(() -> {
            try {
                StringBuilder full = new StringBuilder();
                full.append("========== DeviceResetSpoofer 诊断日志 ==========\n");
                full.append("时间: ").append(new java.util.Date().toString()).append("\n\n");

                // 1. 本机真实值
                full.append("========== 本机真实值 ==========\n");
                try {
                    String androidId = android.provider.Settings.Secure.getString(
                            getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                    full.append("Android ID: ").append(androidId).append("\n");
                } catch (Throwable e) {
                    full.append("Android ID: 读取失败 ").append(e.getMessage()).append("\n");
                }
                full.append("品牌(BRAND): ").append(android.os.Build.BRAND).append("\n");
                full.append("型号(MODEL): ").append(android.os.Build.MODEL).append("\n");
                full.append("厂商(MANUFACTURER): ").append(android.os.Build.MANUFACTURER).append("\n");
                full.append("设备(DEVICE): ").append(android.os.Build.DEVICE).append("\n");
                full.append("产品(PRODUCT): ").append(android.os.Build.PRODUCT).append("\n");
                full.append("硬件(HARDWARE): ").append(android.os.Build.HARDWARE).append("\n");
                full.append("指纹(FINGERPRINT): ").append(android.os.Build.FINGERPRINT).append("\n");
                full.append("Build ID: ").append(android.os.Build.ID).append("\n");
                full.append("Bootloader: ").append(android.os.Build.BOOTLOADER).append("\n");
                full.append("RadioVersion: ").append(android.os.Build.getRadioVersion()).append("\n");
                full.append("Build Time: ").append(new java.util.Date(android.os.Build.TIME).toString()).append("\n");
                full.append("\n");

                // 2. 每个应用的伪装值
                full.append("========== 作用域应用伪装值 ==========\n");
                for (AppItem item : appList) {
                    full.append("\n--- ").append(item.appName).append(" (").append(item.packageName).append(") ---\n");
                    // 重新读取哨兵文件
                    String json = readIdentityFile(item.packageName);
                    if (json != null && json.startsWith("{")) {
                        full.append("哨兵文件: 存在 (").append(json.length()).append(" bytes)\n");
                        full.append("伪装值JSON:\n").append(json).append("\n");
                        // 解析并对比
                        try {
                            org.json.JSONObject obj = new org.json.JSONObject(json);
                            full.append("\n--- 关键字段对比 ---\n");
                            full.append("Android ID: 伪装=").append(obj.optString("androidId", "?"))
                                    .append(" | 真实=").append(android.provider.Settings.Secure.getString(
                                            getContentResolver(), android.provider.Settings.Secure.ANDROID_ID)).append("\n");
                            full.append("品牌: 伪装=").append(obj.optString("brand", "?"))
                                    .append(" | 真实=").append(android.os.Build.BRAND).append("\n");
                            full.append("型号: 伪装=").append(obj.optString("model", "?"))
                                    .append(" | 真实=").append(android.os.Build.MODEL).append("\n");
                            full.append("厂商: 伪装=").append(obj.optString("manufacturer", "?"))
                                    .append(" | 真实=").append(android.os.Build.MANUFACTURER).append("\n");
                            full.append("指纹: 伪装=").append(obj.optString("fingerprint", "?"))
                                    .append("\n  真实=").append(android.os.Build.FINGERPRINT).append("\n");
                            full.append("IMEI: 伪装=").append(obj.optString("imei", "?")).append("\n");
                            full.append("序列号: 伪装=").append(obj.optString("serial", "?")).append("\n");
                            full.append("MAC: 伪装=").append(obj.optString("macAddress", "?")).append("\n");
                        } catch (Throwable e) {
                            full.append("JSON解析失败: ").append(e.getMessage()).append("\n");
                        }
                    } else {
                        full.append("哨兵文件: 不存在（该应用未生成伪装值，或数据已被清除）\n");
                    }
                    full.append("UI状态: hasIdentity=").append(item.hasIdentity)
                            .append(", identityJson长度=").append(item.identityJson == null ? 0 : item.identityJson.length()).append("\n");
                    // UI自身SharedPreferences缓存（也是一个存储位置）
                    try {
                        String cached = getSharedPreferences("devicereset_ui", MODE_PRIVATE)
                                .getString("identity_" + item.packageName, null);
                        full.append("UI缓存[SharedPreferences identity_").append(item.packageName).append("]: ")
                                .append(cached == null ? "无" : "(" + cached.length() + " bytes) " + cached).append("\n");
                    } catch (Throwable ignored) {}
                    // 枚举所有可能存储伪装值的位置：内部多用户 / user_de / data/data / 外部多卷，逐个 ls+cat
                    full.append("------ 所有可能的身份存储位置（逐项列出）------\n");
                    full.append(dumpAllIdentityLocations(item.packageName));
                    // 进程是否在运行
                    try {
                        Process pp = Runtime.getRuntime().exec(new String[]{"su", "-c",
                                "echo '进程PID:'; pidof '" + item.packageName + "' 2>&1"});
                        java.io.BufferedReader pr = new java.io.BufferedReader(
                                new java.io.InputStreamReader(pp.getInputStream()));
                        String pl;
                        while ((pl = pr.readLine()) != null) full.append(pl).append("\n");
                        pr.close();
                        pp.waitFor();
                    } catch (Throwable ignored) {}
                    // logcat兜底：模块最后一次加载记录（免重启也能看到进程实际值）
                    try {
                        Identity logcatId = readRuntimeFromLogcat(item.packageName);
                        full.append("logcat进程实际值: ");
                        if (logcatId != null) {
                            full.append("androidId=").append(logcatId.androidId)
                                .append(" brand=").append(logcatId.brand)
                                .append(" model=").append(logcatId.model).append("\n");
                        } else {
                            full.append("无（该应用最近未启动过，或日志已被冲掉）\n");
                        }
                    } catch (Throwable e) {
                        full.append("logcat进程实际值异常: ").append(e.getMessage()).append("\n");
                    }
                }
                full.append("\n");

                // 3. Xposed模块日志（看模块端写入是否成功）
                full.append("========== Xposed模块日志 ==========\n");
                try {
                    Process logcat = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "logcat -d -t 200 2>/dev/null | grep -iE 'DeviceReset|devicereset|Sentinel|sentinel|identity|Identity' | tail -50"});
                    java.io.BufferedReader lr = new java.io.BufferedReader(
                            new java.io.InputStreamReader(logcat.getInputStream()));
                    String ll;
                    while ((ll = lr.readLine()) != null) full.append(ll).append("\n");
                    lr.close();
                    logcat.waitFor();
                } catch (Throwable e) {
                    full.append("logcat读取失败: ").append(e.getMessage()).append("\n");
                }
                // 也尝试读LSPosed日志文件（遍历全部.log，日志轮转后旧记录在旧文件里，不能只读最新一个）
                try {
                    Process lsp = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "echo '---所有日志文件---'; ls -t /data/adb/lspd/log/*.log 2>/dev/null; "
                          + "echo '---Identity loaded记录---'; grep -h 'Identity loaded' /data/adb/lspd/log/*.log 2>/dev/null | tail -40"});
                    java.io.BufferedReader lr2 = new java.io.BufferedReader(
                            new java.io.InputStreamReader(lsp.getInputStream()));
                    String ll2;
                    while ((ll2 = lr2.readLine()) != null) full.append(ll2).append("\n");
                    lr2.close();
                    lsp.waitFor();
                } catch (Throwable e) {
                    full.append("LSPosed日志读取失败: ").append(e.getMessage()).append("\n");
                }
                full.append("\n");

                // 4. 原始调试日志
                full.append("========== 调试日志 ==========\n");
                full.append(debugLog.toString());

                // 写入文件
                String content = full.toString();
                Process su = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                os.writeBytes("mkdir -p /sdcard/Download\n");
                os.writeBytes("cat > /sdcard/Download/111 << 'DRS_DIAG_EOF'\n");
                os.writeBytes(content + "\n");
                os.writeBytes("DRS_DIAG_EOF\n");
                os.writeBytes("chmod 666 /sdcard/Download/111\n");
                os.writeBytes("ls -l /sdcard/Download/111\n");
                os.writeBytes("exit\n");
                os.flush();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(su.getInputStream()));
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) out.append(line);
                reader.close();
                su.waitFor();
                log("诊断日志已写入 /sdcard/Download/111: " + out);
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

    /** 执行一段su shell脚本，带超时，返回stdout（超时/异常返回null），防止永久阻塞 */
    private String execSuScriptWithTimeout(String script, int timeoutSec, String tag) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes(script);
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            final Process fp = p;
            final StringBuilder out = new StringBuilder();
            Thread rt = new Thread(() -> {
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(fp.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) out.append(l).append("\n");
                    r.close();
                } catch (Throwable ignored) {}
            });
            rt.setDaemon(true);
            rt.start();
            Thread et = new Thread(() -> {
                try {
                    java.io.BufferedReader er = new java.io.BufferedReader(
                            new java.io.InputStreamReader(fp.getErrorStream()));
                    while (er.readLine() != null) {}
                    er.close();
                } catch (Throwable ignored) {}
            });
            et.setDaemon(true);
            et.start();
            boolean finished = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroy();
                log(tag + " 超时(" + timeoutSec + "s)，已中断");
                return null;
            }
            rt.join(1500);
            return out.toString();
        } catch (Throwable e) {
            log(tag + " 异常: " + e.getMessage());
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
            return null;
        }
    }

    /**
     * 枚举一个包在“所有可能存伪装值的位置”的文件，逐个 ls -la 并 cat 内容，用于诊断。
     * 覆盖：内部 /data/user/<用户0,10..15>/<pkg>/files、/data/user_de/...、/data/data/...、
     * 外部 /sdcard 与 /storage/emulated/<用户>/Android/data/<pkg>/files；最后 best-effort find。
     */
    private String dumpAllIdentityLocations(String pkg) {
        StringBuilder sc = new StringBuilder();
        // 内部多用户
        sc.append("echo '### [内部] /data/user/<用户>/").append(pkg).append("/files ###'\n");
        sc.append("for U in 0 10 11 12 13 14 15; do\n");
        sc.append("  D=\"/data/user/$U/").append(pkg).append("/files\"\n");
        sc.append("  echo \"--- $D ---\"; ls -la \"$D\" 2>&1\n");
        sc.append("  for F in .identity_sentinel .identity_runtime; do P=\"$D/$F\"; if [ -f \"$P\" ]; then echo \">>> 内容 $P:\"; cat \"$P\" 2>&1; echo; fi; done\n");
        sc.append("done\n");
        // user_de（设备加密存储，早期启动可能写这里）
        sc.append("echo '### [DE加密] /data/user_de/<用户>/").append(pkg).append("/files ###'\n");
        sc.append("for U in 0 10 11 12 13 14 15; do\n");
        sc.append("  D=\"/data/user_de/$U/").append(pkg).append("/files\"\n");
        sc.append("  echo \"--- $D ---\"; ls -la \"$D\" 2>&1\n");
        sc.append("  for F in .identity_sentinel .identity_runtime; do P=\"$D/$F\"; if [ -f \"$P\" ]; then echo \">>> 内容 $P:\"; cat \"$P\" 2>&1; echo; fi; done\n");
        sc.append("done\n");
        // /data/data 符号链接视图
        sc.append("echo '### [data/data] /data/data/").append(pkg).append("/files ###'\n");
        sc.append("D=\"/data/data/").append(pkg).append("/files\"; echo \"--- $D ---\"; ls -la \"$D\" 2>&1\n");
        sc.append("for F in .identity_sentinel .identity_runtime; do P=\"$D/$F\"; if [ -f \"$P\" ]; then echo \">>> 内容 $P:\"; cat \"$P\" 2>&1; echo; fi; done\n");
        // 外部存储多卷/多用户
        sc.append("echo '### [外部] <卷>/Android/data/").append(pkg).append("/files ###'\n");
        sc.append("for M in /sdcard /storage/emulated/0 /storage/emulated/10 /storage/emulated/11 /storage/emulated/12 /storage/emulated/13 /storage/self/primary; do\n");
        sc.append("  D=\"$M/Android/data/").append(pkg).append("/files\"\n");
        sc.append("  echo \"--- $D ---\"; ls -la \"$D\" 2>&1\n");
        sc.append("  for F in .identity_sentinel .identity_runtime; do P=\"$D/$F\"; if [ -f \"$P\" ]; then echo \">>> 内容 $P:\"; cat \"$P\" 2>&1; echo; fi; done\n");
        sc.append("done\n");
        // best-effort 全盘查找该包相关的身份文件
        sc.append("echo '### [find] 该包所有 .identity* 文件 ###'\n");
        sc.append("find /data/data/").append(pkg).append(" /sdcard/Android/data/").append(pkg)
          .append(" -name '.identity*' -exec ls -la {} \\; 2>/dev/null\n");
        sc.append("for U in 0 10 11 12 13 14 15; do find /data/user/$U/").append(pkg)
          .append(" -name '.identity*' -exec ls -la {} \\; 2>/dev/null; done\n");
        String out = execSuScriptWithTimeout(sc.toString(), 15, "枚举身份位置");
        return out == null ? "(枚举超时或失败)\n" : out;
    }

    private Set<String> scanIdentityPackages() {
        Set<String> result = new HashSet<>();
        try {
            StringBuilder script = new StringBuilder();
            script.append("for u in /data/user/*/; do\n");
            script.append("  for d in \"$u\"*/; do\n");
            script.append("    pkg=$(basename \"$d\")\n");
            script.append("    if [ -f \"$d/files/.identity_sentinel\" ]; then echo \"$pkg\"; fi\n");
            script.append("  done\n");
            script.append("done\n");
            script.append("for d in /data/data/*/; do\n");
            script.append("  pkg=$(basename \"$d\")\n");
            script.append("  if [ -f \"$d/files/.identity_sentinel\" ]; then echo \"$pkg\"; fi\n");
            script.append("done\n");
            String raw = execSuScriptWithTimeout(script.toString(), 10, "哨兵扫描");
            if (raw != null) {
                for (String line : raw.split("\n")) {
                    line = line.trim();
                    if (!line.isEmpty() && isValidPackageName(line)) result.add(line);
                }
            }
        } catch (Throwable e) {
            log("哨兵扫描异常: " + e.getMessage());
        }
        log("哨兵扫描结果: " + result);
        return result;
    }

    private String readIdentityFile(String packageName) {
        List<String> paths = buildCandidatePaths(packageName, ".identity_sentinel");
        return readFirstExistingFile(paths, packageName, "哨兵");
    }

    /** 构建某文件在各用户/外部存储下的全部候选路径 */
    private List<String> buildCandidatePaths(String packageName, String fileName) {
        List<String> paths = new ArrayList<>();
        int[] userIds = {0, 10, 11, 12, 13, 14, 15};
        for (int uid : userIds) {
            paths.add("/data/user/" + uid + "/" + packageName + "/files/" + fileName);
            paths.add("/data/user_de/" + uid + "/" + packageName + "/files/" + fileName);
        }
        paths.add("/data/data/" + packageName + "/files/" + fileName);
        // 外部存储备份路径（模块在目标APP进程内写入，UI通过root读取）
        paths.add("/sdcard/Android/data/" + packageName + "/files/" + fileName);
        paths.add("/storage/emulated/0/Android/data/" + packageName + "/files/" + fileName);
        return paths;
    }

    /**
     * 在单个su进程内依次尝试多个路径，返回第一个存在且内容为JSON的文件。
     * 带8秒超时，避免某个root命令永久waitFor导致界面卡在"正在扫描"。
     */
    private String readFirstExistingFile(List<String> paths, String tag, String kind) {
        Process p = null;
        try {
            StringBuilder script = new StringBuilder();
            for (String path : paths) {
                script.append("if [ -f '").append(path).append("' ]; then ")
                      .append("echo '===DRS_BEGIN==='; cat '").append(path)
                      .append("'; echo; echo '===DRS_END==='; exit 0; fi\n");
            }
            script.append("echo '===DRS_NONE==='\n");
            p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes(script.toString());
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            final Process fp = p;
            final StringBuilder out = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(fp.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) out.append(l).append("\n");
                    // 主动消费stderr，防止缓冲区填满阻塞
                    try {
                        java.io.BufferedReader er = new java.io.BufferedReader(
                                new java.io.InputStreamReader(fp.getErrorStream()));
                        while (er.readLine() != null) {}
                        er.close();
                    } catch (Throwable ignored) {}
                    r.close();
                } catch (Throwable ignored) {}
            });
            readerThread.setDaemon(true);
            readerThread.start();
            boolean finished = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroy();
                log(kind + "读取超时(8s) " + tag);
                return null;
            }
            readerThread.join(1500);
            String all = out.toString();
            int b = all.indexOf("===DRS_BEGIN===");
            int e = all.indexOf("===DRS_END===");
            if (b >= 0 && e > b) {
                String content = all.substring(b + "===DRS_BEGIN===".length(), e).trim();
                if (content.startsWith("{")) {
                    log(kind + "读取成功 " + tag + " (" + content.length() + " bytes)");
                    return content;
                }
            }
            // 明确收到 DRS_NONE = 所有路径确实都没有文件 → 返回空串(区别于读取失败null)
            if (all.contains("===DRS_NONE===")) {
                log(kind + "确认无文件 " + tag);
                return "";
            }
            // 输出为空/无标记 = su异常或被中断，返回null表示"不确定"，调用方不得据此清空已有值
            log(kind + "读取结果不确定(保留原值) " + tag + " out=" + all.substring(0, Math.min(40, all.length())));
            return null;
        } catch (Throwable ex) {
            log(kind + "读取异常 " + tag + ": " + ex.getMessage());
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
            return null;
        }
    }

    /** 带重试的读取，最多2次；确认无文件(空串)立即返回，超时/出错返回null */
    private String readIdentityFileWithRetry(String packageName) {
        for (int i = 0; i < 2; i++) {
            String json = readIdentityFile(packageName);
            if (json != null && json.startsWith("{")) return json;
            if (json != null && json.isEmpty()) return ""; // 明确无文件，不必重试
            try { Thread.sleep(300); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 读取目标进程当前真正在用的运行时值（模块每次加载时写.identity_runtime，UI不写） */
    private String readRuntimeFile(String packageName) {
        List<String> paths = buildCandidatePaths(packageName, ".identity_runtime");
        return readFirstExistingFile(paths, packageName, "运行时");
    }

    /**
     * 兜底读取进程实际加载值：模块启动时会打印
     * "[DeviceReset] Identity loaded: androidId=.., model=.., brand=.."。
     * 两个来源：①LSPosed持久化日志文件 /data/adb/lspd/log/*.log（跨时间保留，需遍历全部，
     * 只读最新一个会因日志轮转漏掉旧记录）；②logcat实时环形缓冲（最近启动才有）。
     * 优先实时logcat，没有再用持久化日志。
     */
    private Identity readRuntimeFromLogcat(String packageName) {
        try {
            StringBuilder script = new StringBuilder();
            script.append("echo '===FILE==='\n");
            script.append("grep -h 'Identity loaded' /data/adb/lspd/log/*.log 2>/dev/null")
                  .append(" | grep '").append(packageName).append("' | tail -1\n");
            script.append("echo '===LIVE==='\n");
            script.append("logcat -d 2>/dev/null | grep '").append(packageName)
                  .append("' | grep 'Identity loaded' | tail -1\n");
            String out = execSuScriptWithTimeout(script.toString(), 10, "运行时日志");
            if (out == null || out.trim().isEmpty()) {
                log("运行时日志无记录 " + packageName);
                return null;
            }
            String fileLine = null, liveLine = null;
            String mode = null;
            for (String raw : out.split("\n")) {
                String l = raw.trim();
                if (l.contains("===FILE===")) { mode = "file"; continue; }
                if (l.contains("===LIVE===")) { mode = "live"; continue; }
                if (!l.contains("Identity loaded")) continue;
                if ("live".equals(mode) && l.contains("androidId=")) liveLine = l;
                else if ("file".equals(mode) && l.contains("androidId=")) fileLine = l;
            }
            // 优先实时（最近启动），其次持久化文件
            String line = (liveLine != null) ? liveLine : fileLine;
            if (line == null) {
                log("运行时日志解析为空 " + packageName);
                return null;
            }
            log("运行时实际加载记录[" + (liveLine != null ? "logcat" : "lspd日志") + "]: " + line);
            String aid = extractField(line, "androidId=", ',');
            if (aid == null || aid.isEmpty()) return null;
            Identity id = new Identity();
            id.androidId = aid;
            id.model = extractField(line, "model=", ',');
            id.brand = extractField(line, "brand=", null);
            id.manufacturer = id.brand;
            return id;
        } catch (Throwable e) {
            log("运行时日志解析异常: " + e.getMessage());
            return null;
        }
    }

    /** 从 "key=value," 或行尾 "key=value" 中取值 */
    private String extractField(String line, String key, Character stop) {
        int i = line.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = line.length();
        if (stop != null) {
            int j = line.indexOf(stop, start);
            if (j >= 0) end = j;
        }
        return line.substring(start, end).trim();
    }

    /** 判断目标应用进程当前是否正在运行（返回进程名列表字符串） */
    private String getRunningProcessInfo(String packageName) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "pidof '" + packageName + "' 2>/dev/null; ps -A 2>/dev/null | grep '" + packageName + "' | awk '{print $NF}' | sort -u"});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (!l.isEmpty()) sb.append(l).append(" ");
            }
            r.close();
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 读不到伪装值时的提示：模块在目标应用每次启动时自动生成并应用，无需也不再提供手动写入 */
    private void showAutoOnlyInfo(AppItem item, boolean zh, boolean en) {
        String msg, ok;
        if (zh) {
            msg = "暂未读取到该应用的伪装值。\n模块会在该应用每次启动时自动生成并应用，无需手动操作。\n请先打开一次该应用，再回到这里查看。";
            ok = "确定";
        } else if (en) {
            msg = "No identity read yet.\nThe module auto-generates and applies one every time the app launches — no manual action needed.\nOpen the app once, then return here to view.";
            ok = "OK";
        } else {
            msg = "Подмена ещё не прочитана.\nМодуль автоматически создаёт и применяет её при каждом запуске приложения — вручную ничего делать не нужно.\nОткройте приложение один раз и вернитесь сюда.";
            ok = "ОК";
        }
        new AlertDialog.Builder(this).setTitle(item.appName).setMessage(msg)
                .setPositiveButton(ok, null).show();
    }

    private void showIdentityDialog(AppItem item) {
        boolean zh = LANG_ZH.equals(currentLang);
        boolean en = LANG_EN.equals(currentLang);
        // 先重新读哨兵文件；读取不确定(null)时回退列表已读到的值，不轻易判定为无
        String json = readIdentityFileWithRetry(item.packageName);
        if (json == null && item.identityJson != null) {
            // 本次读取超时/出错，沿用列表扫描时已读到的值
            json = item.identityJson;
            log("点击时读取不确定，沿用已缓存值 " + item.packageName + " (" + json.length() + " bytes)");
        }
        boolean spoofed = false;
        if (json != null && json.startsWith("{")) {
            spoofed = isActuallySpoofed(json);
            item.identityJson = json;
            item.hasIdentity = spoofed;
            getSharedPreferences("devicereset_ui", MODE_PRIVATE).edit()
                    .putString("identity_" + item.packageName, json).apply();
        } else {
            // 明确确认无文件（""），清除旧缓存
            item.identityJson = null;
            item.hasIdentity = false;
            getSharedPreferences("devicereset_ui", MODE_PRIVATE).edit()
                    .remove("identity_" + item.packageName).apply();
            json = null;
        }
        // 读不到任何身份文件 → 只提示模块会自动生成（已移除手动生成，不再写入干扰值）
        if (json == null) {
            showAutoOnlyInfo(item, zh, en);
            return;
        }
        Identity savedId = Identity.fromJson(item.identityJson);
        if (savedId == null) {
            String title = zh ? "解析失败" : en ? "Parse Error" : "Ошибка парсинга";
            String ok = zh ? "确定" : en ? "OK" : "ОК";
            new AlertDialog.Builder(this).setTitle(title).setPositiveButton(ok, null).show();
            return;
        }
        // 显示优先级（以app的files目录里存的完整值为准）：
        // ①.identity_runtime 运行值(完整26字段) ②.identity_sentinel 已存完整值
        // ③日志加载记录(仅androidId/brand/model三字段)只在两个文件都读不到时最后兜底，
        //   避免日志里的旧残余覆盖目录里的权威完整值
        Identity id = Identity.fromJson(readRuntimeFile(item.packageName));
        String valueSource = "runtime文件";
        if (id == null) {
            id = savedId;
            valueSource = "sentinel已存值";
        }
        if (id == null) {
            id = readRuntimeFromLogcat(item.packageName);
            valueSource = "日志兜底(仅3字段)";
        }
        log("实际值来源 " + item.packageName + ": " + valueSource
                + (id != null ? " aid=" + id.androidId + " brand=" + id.brand + " model=" + id.model : " 无"));
        if (id == null) {
            // runtime/sentinel/日志三种来源都没有 → 只提示自动生成
            showAutoOnlyInfo(item, zh, en);
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
        String lTitle = zh ? "应用实际使用值（实际 → 原始）" : en ? "Actual value in use (actual → original)" : "Фактическое значение (факт → оригинал)";
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
