package io.github.gjr787878.devicereset.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import io.github.gjr787878.devicereset.Config;
import io.github.gjr787878.devicereset.GlassButtonDrawable;
import io.github.gjr787878.devicereset.xposed.Identity;
import io.github.gjr787878.devicereset.xposed.IdentityGenerator;
import io.github.gjr787878.devicereset.xposed.SentinelDetector;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_LANG = "app_language";
    private static final String LANG_ZH = "zh";
    private static final String LANG_EN = "en";
    private static final String LANG_RU = "ru";
    private static final String UPDATE_REPO = "GJR787878/DeviceResetSpooferX";
    private static final String REPO_HOME_URL = "https://github.com/GJR787878/DeviceResetSpooferX";

    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_BLUE = 0xFF0A84FF;
    private static final int COLOR_GRAY = 0xFFCCCCCC;
    private static final int COLOR_DIALOG_BG = 0xFF1C1C1E;

    private String currentLang;
    private FrameLayout contentFrame;
    private Button navApps, navSettings;
    private LinearLayout appListContainer;
    private TextView appCountText;
    private GlassButtonDrawable[] hookGlass = new GlassButtonDrawable[7];
    private TextView[] hookTv = new TextView[7];

    // ===== 三语字符串 =====
    private String t(String zh, String en, String ru) {
        if (LANG_ZH.equals(currentLang)) return zh;
        if (LANG_RU.equals(currentLang)) return ru;
        return en;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("devicereset_ui", MODE_PRIVATE);
        currentLang = prefs.getString(PREFS_LANG, LANG_EN);
        buildRootUI();
        checkUpdate(false);
    }

    private void buildRootUI() {
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        contentFrame = new FrameLayout(this);
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        contentLp.bottomMargin = Math.round(80 * d);
        root.addView(contentFrame, contentLp);

        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER);
        GradientDrawable navBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF06A6A72, 0x882C2C2E});
        navBg.setCornerRadius(28 * d);
        navBg.setStroke(Math.round(1 * d), 0x55FFFFFF);
        navBar.setBackground(navBg);
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(64 * d));
        navLp.gravity = Gravity.BOTTOM;
        navLp.leftMargin = Math.round(16 * d);
        navLp.rightMargin = Math.round(16 * d);
        navLp.bottomMargin = Math.round(16 * d);
        root.addView(navBar, navLp);

        navApps = makeNavButton();
        navSettings = makeNavButton();
        LinearLayout.LayoutParams navBtnLp = new LinearLayout.LayoutParams(
                0, Math.round(48 * d), 1f);
        navBtnLp.leftMargin = Math.round(4 * d);
        navBtnLp.rightMargin = Math.round(4 * d);
        navBar.addView(navApps, navBtnLp);
        navBar.addView(navSettings, navBtnLp);

        setContentView(root);
        switchTab(0);
    }

    private Button makeNavButton() {
        Button b = new Button(this);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private void switchTab(int idx) {
        boolean isApps = idx == 0;
        contentFrame.removeAllViews();
        contentFrame.addView(isApps ? buildAppsPage() : buildSettingsPage());
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable selBg = new GradientDrawable();
        selBg.setColor(0x330A84FF);
        selBg.setCornerRadius(20 * d);
        navApps.setText(t("应用", "Apps", "Приложения"));
        navSettings.setText(t("设置", "Settings", "Настройки"));
        navApps.setTextColor(isApps ? COLOR_BLUE : COLOR_WHITE);
        navSettings.setTextColor(isApps ? COLOR_WHITE : COLOR_BLUE);
        navApps.setBackground(isApps ? selBg : null);
        navSettings.setBackground(isApps ? null : selBg);
        navApps.setOnClickListener(v -> switchTab(0));
        navSettings.setOnClickListener(v -> switchTab(1));
    }

    // ===== 应用页 =====
    private View buildAppsPage() {
        float d = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(Math.round(24 * d), Math.round(48 * d), Math.round(24 * d), Math.round(32 * d));

        TextView title = new TextView(this);
        title.setText(t("选择应用", "Select App", "Выберите приложение"));
        title.setTextSize(28);
        title.setTextColor(COLOR_WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        ll.addView(title);

        appCountText = new TextView(this);
        appCountText.setText("");
        appCountText.setTextSize(13);
        appCountText.setTextColor(COLOR_GRAY);
        appCountText.setGravity(Gravity.END);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.topMargin = Math.round(8 * d);
        ll.addView(appCountText, countLp);

        appListContainer = new LinearLayout(this);
        appListContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = Math.round(16 * d);
        ll.addView(appListContainer, listLp);

        sv.addView(ll);
        refreshAppList();
        return sv;
    }

    private void refreshAppList() {
        appListContainer.removeAllViews();
        appCountText.setText(t("扫描中...", "Scanning...", "Сканирование..."));
        new Thread(() -> {
            final java.util.List<String> pkgs = scanPackagesWithIdentity();
            final PackageManager pm = getPackageManager();
            final android.graphics.drawable.Drawable defaultIcon = getResources().getDrawable(android.R.drawable.sym_def_app_icon);
            final float d = getResources().getDisplayMetrics().density;
            // 后台线程加载每个应用的名称和图标（多策略）
            final java.util.List<Object[]> items = new java.util.ArrayList<>();
            for (String pkg : pkgs) {
                String name = pkg;
                android.graphics.drawable.Drawable icon = defaultIcon;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
                    // 名称
                    try {
                        CharSequence label = ai.loadLabel(pm);
                        if (label != null && !label.toString().equals(pkg)) name = label.toString();
                    } catch (Throwable ignored) {}
                    // 图标方式1：从APK文件读取（绕过挂起/休眠）
                    try {
                        String apkPath = ai.publicSourceDir != null ? ai.publicSourceDir : ai.sourceDir;
                        if (apkPath != null && new java.io.File(apkPath).exists()) {
                            android.content.pm.PackageInfo archiveInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA);
                            if (archiveInfo != null && archiveInfo.applicationInfo != null) {
                                archiveInfo.applicationInfo.sourceDir = apkPath;
                                archiveInfo.applicationInfo.publicSourceDir = apkPath;
                                android.graphics.drawable.Drawable ic = pm.getApplicationIcon(archiveInfo.applicationInfo);
                                if (ic != null) icon = ic;
                            }
                        }
                    } catch (Throwable ignored) {}
                    // 图标方式2：getResourcesForApplication
                    if (icon == defaultIcon) {
                        try {
                            android.content.res.Resources res = pm.getResourcesForApplication(ai);
                            if (ai.icon != 0) {
                                android.graphics.drawable.Drawable ic = res.getDrawable(ai.icon, null);
                                if (ic != null) icon = ic;
                            }
                        } catch (Throwable ignored) {}
                    }
                    // 图标方式3：pm.getApplicationIcon(pkg)
                    if (icon == defaultIcon) {
                        try {
                            android.graphics.drawable.Drawable ic = pm.getApplicationIcon(pkg);
                            if (ic != null) icon = ic;
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable e) {
                    // 兜底：root查找APK路径
                    String apkPath = findApkPathViaRoot(pkg);
                    if (apkPath != null) {
                        try {
                            android.content.pm.PackageInfo archiveInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA);
                            if (archiveInfo != null && archiveInfo.applicationInfo != null) {
                                archiveInfo.applicationInfo.sourceDir = apkPath;
                                archiveInfo.applicationInfo.publicSourceDir = apkPath;
                                try {
                                    CharSequence label = archiveInfo.applicationInfo.loadLabel(pm);
                                    if (label != null && !label.toString().equals(pkg)) name = label.toString();
                                } catch (Throwable ignored) {}
                                try {
                                    android.graphics.drawable.Drawable ic = pm.getApplicationIcon(archiveInfo.applicationInfo);
                                    if (ic != null) icon = ic;
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                items.add(new Object[]{name, pkg, icon});
            }
            runOnUiThread(() -> {
                appCountText.setText(pkgs.size() + t(" 个应用有伪装值", " app(s) with spoofed identity", " приложений с подменённой идентичностью"));
                if (pkgs.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setText(t("未找到有伪装值的应用。\n请确保目标应用已在 LSPosed 作用域中勾选并至少运行过一次。",
                            "No apps with spoofed identity found.\nEnsure target apps are checked in LSPosed scope and launched at least once.",
                            "Не найдено приложений с подменённой идентичностью.\nУбедитесь, что целевые приложения отмечены в области LSPosed и запускались хотя бы раз."));
                    empty.setTextSize(14);
                    empty.setTextColor(COLOR_GRAY);
                    empty.setPadding(0, 40, 0, 0);
                    appListContainer.addView(empty);
                    return;
                }
                for (Object[] item : items) {
                    appListContainer.addView(makeAppRow((String) item[0], (String) item[1], (android.graphics.drawable.Drawable) item[2], d));
                }
            });
        }).start();
    }

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
                    reader.close();
                    su.waitFor();
                    return line.substring(8).trim();
                }
            }
            reader.close();
            su.waitFor();
        } catch (Throwable ignored) {}
        return null;
    }

    private View makeAppRow(String name, String pkg, android.graphics.drawable.Drawable icon, float d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(16 * d), Math.round(14 * d), Math.round(16 * d), Math.round(14 * d));
        row.setBackground(new GlassButtonDrawable(24 * d, 1 * d, false));

        ImageView iconView = new ImageView(this);
        iconView.setLayoutParams(new LinearLayout.LayoutParams(Math.round(48 * d), Math.round(48 * d)));
        iconView.setImageDrawable(icon);
        row.addView(iconView);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = Math.round(14 * d);
        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(16);
        tvName.setTextColor(COLOR_WHITE);
        TextView tvPkg = new TextView(this);
        tvPkg.setText(pkg);
        tvPkg.setTextSize(12);
        tvPkg.setTextColor(COLOR_GRAY);
        textCol.addView(tvName);
        textCol.addView(tvPkg);
        row.addView(textCol, textLp);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setColor(COLOR_BLUE);
        dotBg.setShape(GradientDrawable.OVAL);
        dot.setBackground(dotBg);
        row.addView(dot, new LinearLayout.LayoutParams(Math.round(12 * d), Math.round(12 * d)));

        row.setOnClickListener(v -> showAppDetailDialog(pkg, name));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = Math.round(10 * d);
        row.setLayoutParams(rowLp);
        return row;
    }

    // ===== 应用详情弹窗 =====
    private void showAppDetailDialog(String pkg, String appName) {
        String json = readIdentityFile(pkg);
        if (json == null) { Toast.makeText(this, t("读取失败", "Read failed", "Ошибка чтения"), Toast.LENGTH_SHORT).show(); return; }
        Identity id = Identity.fromJson(json);
        if (id == null) { Toast.makeText(this, t("解析失败", "Parse failed", "Ошибка парсинга"), Toast.LENGTH_SHORT).show(); return; }

        float d = getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Math.round(20 * d), Math.round(20 * d), Math.round(20 * d), Math.round(16 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_DIALOG_BG);
        bg.setCornerRadius(24 * d);
        root.setBackground(bg);

        TextView title = new TextView(this);
        title.setText(t("应用: ", "App: ", "Приложение: ") + appName + "\n" + pkg);
        title.setTextSize(16);
        title.setTextColor(COLOR_WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svLp.topMargin = Math.round(12 * d);
        svLp.bottomMargin = Math.round(12 * d);

        TextView content = new TextView(this);
        content.setTextSize(13);
        content.setTextColor(COLOR_GRAY);
        StringBuilder sb = new StringBuilder();
        String lAndroidId = t("Android ID: ", "Android ID: ", "Android ID: ");
        String lAdId = t("广告ID: ", "Ad ID: ", "Рекл. ID: ");
        String lAppSet = t("AppSet ID: ", "AppSet ID: ", "AppSet ID: ");
        String lImei = t("IMEI: ", "IMEI: ", "IMEI: ");
        String lMeid = t("MEID: ", "MEID: ", "MEID: ");
        String lSerial = t("序列号: ", "Serial: ", "Серийный: ");
        String lMac = t("MAC: ", "MAC: ", "MAC: ");
        String lGsf = t("GSF ID: ", "GSF ID: ", "GSF ID: ");
        String lBrand = t("品牌: ", "Brand: ", "Бренд: ");
        String lModel = t("型号: ", "Model: ", "Модель: ");
        String lMfr = t("厂商: ", "Manufacturer: ", "Производитель: ");
        String lFp = t("指纹: ", "Fingerprint: ", "Отпечаток: ");
        String lBuild = t("Build ID: ", "Build ID: ", "Build ID: ");
        String lCarrier = t("运营商: ", "Carrier: ", "Оператор: ");
        String lCarrierCode = t("运营商代码: ", "Carrier Code: ", "Код оператора: ");
        String lImsi = t("IMSI: ", "IMSI: ", "IMSI: ");
        String lIccid = t("ICCID: ", "ICCID: ", "ICCID: ");

        if (id.androidId != null) sb.append(lAndroidId).append(id.androidId).append("\n");
        if (id.advertisingId != null) sb.append(lAdId).append(id.advertisingId).append("\n");
        if (id.appSetId != null) sb.append(lAppSet).append(id.appSetId).append("\n");
        if (id.imei != null) sb.append(lImei).append(id.imei).append("\n");
        if (id.meid != null) sb.append(lMeid).append(id.meid).append("\n");
        if (id.serial != null) sb.append(lSerial).append(id.serial).append("\n");
        if (id.macAddress != null) sb.append(lMac).append(id.macAddress).append("\n");
        if (id.gsfId != null) sb.append(lGsf).append(id.gsfId).append("\n");
        sb.append("\n");
        if (id.brand != null) sb.append(lBrand).append(id.brand).append("\n");
        if (id.model != null) sb.append(lModel).append(id.model).append("\n");
        if (id.manufacturer != null) sb.append(lMfr).append(id.manufacturer).append("\n");
        if (id.fingerprint != null) sb.append(lFp).append(id.fingerprint).append("\n");
        if (id.buildId != null) sb.append(lBuild).append(id.buildId).append("\n");
        sb.append("\n");
        if (id.networkOperatorName != null) sb.append(lCarrier).append(id.networkOperatorName).append("\n");
        if (id.networkOperator != null) sb.append(lCarrierCode).append(id.networkOperator).append("\n");
        if (id.imsi != null) sb.append(lImsi).append(id.imsi).append("\n");
        if (id.iccid != null) sb.append(lIccid).append(id.iccid).append("\n");
        content.setText(sb.toString());
        sv.addView(content);
        root.addView(sv, svLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnCustom = makeGlassBtn(t("自定义", "Customize", "Настроить"), 13);
        Button btnRandom = makeGlassBtn(t("随机", "Random", "Случайно"), 13);
        Button btnBack = makeGlassBtn(t("返回", "Back", "Назад"), 13);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.leftMargin = Math.round(3 * d);
        btnLp.rightMargin = Math.round(3 * d);
        btnRow.addView(btnCustom, btnLp);
        btnRow.addView(btnRandom, btnLp);
        btnRow.addView(btnBack, btnLp);
        root.addView(btnRow);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(Math.round(340 * d), Math.round(520 * d));
            w.setGravity(Gravity.CENTER);
            w.setDimAmount(0.6f);
        }
        dialog.show();

        btnBack.setOnClickListener(v -> dialog.dismiss());
        btnRandom.setOnClickListener(v -> {
            Identity newId = IdentityGenerator.generateRandom();
            writeIdentityFile(pkg, newId.toJson());
            Toast.makeText(this, t("已生成随机身份", "Random identity generated", "Случайная идентичность создана"), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            refreshAppList();
        });
        btnCustom.setOnClickListener(v -> {
            dialog.dismiss();
            showCustomizeDialog(pkg, appName, id);
        });
    }

    private Button makeGlassBtn(String text, float size) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(size);
        b.setTextColor(COLOR_WHITE);
        b.setAllCaps(false);
        float d = getResources().getDisplayMetrics().density;
        b.setBackground(new GlassButtonDrawable(24 * d, 1 * d, false));
        return b;
    }

    // ===== 自定义界面 =====
    interface FieldSetter { void set(String val); }

    private void showCustomizeDialog(String pkg, String appName, Identity currentId) {
        float d = getResources().getDisplayMetrics().density;
        final Identity[] workingId = {currentId != null ? currentId : IdentityGenerator.generateRandom()};

        Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(Math.round(24 * d), Math.round(40 * d), Math.round(24 * d), Math.round(24 * d));

        TextView title = new TextView(this);
        title.setText(t("自定义身份", "Customize Identity", "Настройка идентичности"));
        title.setTextSize(22);
        title.setTextColor(COLOR_WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText(appName + " (" + pkg + ")");
        sub.setTextSize(13);
        sub.setTextColor(COLOR_GRAY);
        root.addView(sub);

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svLp.topMargin = Math.round(16 * d);
        svLp.bottomMargin = Math.round(12 * d);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);

        addSectionLabel(form, t("设备型号", "Device Profile", "Модель устройства"), d);
        Button deviceBtn = makeGlassBtn(t("设备: ", "Device: ", "Устройство: ") + (workingId[0].model != null ? workingId[0].model : t("点击选择", "Tap to select", "Нажмите для выбора")), 14);
        form.addView(deviceBtn, makeFormLp(d));
        deviceBtn.setOnClickListener(v -> showDevicePicker(workingId[0], deviceBtn));

        addSectionLabel(form, t("运营商", "Carrier", "Оператор"), d);
        Button carrierBtn = makeGlassBtn(t("运营商: ", "Carrier: ", "Оператор: ") + (workingId[0].networkOperatorName != null ? workingId[0].networkOperatorName : t("点击选择", "Tap to select", "Нажмите для выбора")), 14);
        form.addView(carrierBtn, makeFormLp(d));
        carrierBtn.setOnClickListener(v -> showCarrierPicker(workingId[0], carrierBtn));

        addSectionLabel(form, t("标识符", "Identifiers", "Идентификаторы"), d);
        addFieldRow(form, "Android ID", workingId[0].androidId, d, val -> workingId[0].androidId = val, true);
        addFieldRow(form, t("广告ID", "Ad ID (AAID)", "Рекл. ID"), workingId[0].advertisingId, d, val -> workingId[0].advertisingId = val, true);
        addFieldRow(form, "AppSet ID", workingId[0].appSetId, d, val -> workingId[0].appSetId = val, true);
        addFieldRow(form, "GSF ID", workingId[0].gsfId, d, val -> workingId[0].gsfId = val, true);
        addFieldRow(form, "IMEI", workingId[0].imei, d, val -> workingId[0].imei = val, true);
        addFieldRow(form, "MEID", workingId[0].meid, d, val -> workingId[0].meid = val, true);
        addFieldRow(form, t("序列号", "Serial", "Серийный"), workingId[0].serial, d, val -> workingId[0].serial = val, true);
        addFieldRow(form, t("MAC地址", "MAC Address", "MAC-адрес"), workingId[0].macAddress, d, val -> workingId[0].macAddress = val, true);

        addSectionLabel(form, t("Build信息", "Build Info", "Build инфо"), d);
        addFieldRow(form, "Build ID", workingId[0].buildId, d, val -> workingId[0].buildId = val, true);
        addFieldRow(form, "Bootloader", workingId[0].bootloader, d, val -> workingId[0].bootloader = val, true);
        addFieldRow(form, t("Radio版本", "Radio Version", "Версия Radio"), workingId[0].radioVersion, d, val -> workingId[0].radioVersion = val, true);

        addSectionLabel(form, t("SIM信息", "SIM Info", "SIM инфо"), d);
        addFieldRow(form, "IMSI", workingId[0].imsi, d, val -> workingId[0].imsi = val, true);
        addFieldRow(form, "ICCID", workingId[0].iccid, d, val -> workingId[0].iccid = val, true);

        sv.addView(form);
        root.addView(sv, svLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnSave = makeGlassBtn(t("保存", "Save", "Сохранить"), 15);
        Button btnCancel = makeGlassBtn(t("取消", "Cancel", "Отмена"), 15);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = Math.round(4 * d);
        blp.rightMargin = Math.round(4 * d);
        btnRow.addView(btnCancel, blp);
        btnRow.addView(btnSave, blp);
        root.addView(btnRow);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setDimAmount(0f);
        }
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            writeIdentityFile(pkg, workingId[0].toJson());
            Toast.makeText(this, t("身份已保存", "Identity saved", "Идентичность сохранена"), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            refreshAppList();
        });
    }

    private void addFieldRow(LinearLayout form, String label, String currentVal, float d, FieldSetter setter, boolean canRandom) {
        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(12);
        lbl.setTextColor(COLOR_GRAY);
        form.addView(lbl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        EditText et = new EditText(this);
        et.setText(currentVal != null ? currentVal : "");
        et.setTextSize(13);
        et.setTextColor(COLOR_WHITE);
        et.setHintTextColor(0x66FFFFFF);
        et.setBackgroundColor(0x22FFFFFF);
        et.setPadding(Math.round(12 * d), Math.round(10 * d), Math.round(12 * d), Math.round(10 * d));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(et, etLp);

        if (canRandom) {
            Button rnd = makeGlassBtn(t("随机", "Rnd", "Случ"), 11);
            LinearLayout.LayoutParams rndLp = new LinearLayout.LayoutParams(Math.round(60 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
            rndLp.leftMargin = Math.round(8 * d);
            row.addView(rnd, rndLp);
            rnd.setOnClickListener(v -> {
                Identity tmp = IdentityGenerator.generateRandom();
                String newVal = "?";
                if ("Android ID".equals(label)) newVal = tmp.androidId;
                else if (label.contains("Ad ID") || label.contains("广告")) newVal = tmp.advertisingId;
                else if ("AppSet ID".equals(label)) newVal = tmp.appSetId;
                else if ("GSF ID".equals(label)) newVal = tmp.gsfId;
                else if ("IMEI".equals(label)) newVal = tmp.imei;
                else if ("MEID".equals(label)) newVal = tmp.meid;
                else if (label.contains("Serial") || label.contains("序列")) newVal = tmp.serial;
                else if (label.contains("MAC")) newVal = tmp.macAddress;
                else if ("Build ID".equals(label)) newVal = tmp.buildId;
                else if ("Bootloader".equals(label)) newVal = tmp.bootloader;
                else if (label.contains("Radio")) newVal = tmp.radioVersion;
                else if ("ICCID".equals(label)) newVal = tmp.iccid;
                et.setText(newVal);
            });
        }
        et.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) setter.set(et.getText().toString().trim());
        });
        form.addView(row, makeFormLp(d));
    }

    private LinearLayout.LayoutParams makeFormLp(float d) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(10 * d);
        return lp;
    }

    private void addSectionLabel(LinearLayout form, String text, float d) {
        TextView lbl = new TextView(this);
        lbl.setText(text);
        lbl.setTextSize(15);
        lbl.setTextColor(COLOR_WHITE);
        lbl.setTypeface(lbl.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(16 * d);
        lp.bottomMargin = Math.round(8 * d);
        form.addView(lbl, lp);
    }

    private void showDevicePicker(Identity id, Button updateBtn) {
        try {
            java.lang.reflect.Field f = IdentityGenerator.class.getDeclaredField("DEVICE_PROFILES");
            f.setAccessible(true);
            String[][] profiles = (String[][]) f.get(null);
            String[] names = new String[profiles.length];
            for (int i = 0; i < profiles.length; i++) names[i] = profiles[i][0] + " " + profiles[i][1];
            new AlertDialog.Builder(this)
                    .setTitle(t("选择设备", "Select Device", "Выберите устройство"))
                    .setItems(names, (d, which) -> {
                        String[] p = profiles[which];
                        id.brand = p[0]; id.model = p[1]; id.manufacturer = p[2];
                        id.device = p[3]; id.product = p[4]; id.hardware = p[5]; id.fingerprint = p[6];
                        updateBtn.setText(t("设备: ", "Device: ", "Устройство: ") + p[1]);
                    })
                    .setNegativeButton(t("取消", "Cancel", "Отмена"), null)
                    .show();
        } catch (Throwable e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showCarrierPicker(Identity id, Button updateBtn) {
        try {
            java.lang.reflect.Field f = IdentityGenerator.class.getDeclaredField("CARRIER_PROFILES");
            f.setAccessible(true);
            String[][] carriers = (String[][]) f.get(null);
            String[] names = new String[carriers.length];
            for (int i = 0; i < carriers.length; i++) names[i] = carriers[i][1] + " (" + carriers[i][0] + ")";
            new AlertDialog.Builder(this)
                    .setTitle(t("选择运营商", "Select Carrier", "Выберите оператора"))
                    .setItems(names, (d, which) -> {
                        String[] c = carriers[which];
                        id.networkOperator = c[0]; id.networkOperatorName = c[1];
                        id.simOperator = c[0]; id.simOperatorName = c[1];
                        id.simCountryIso = c[2]; id.networkCountryIso = c[2];
                        Identity tmp = IdentityGenerator.generateRandom();
                        id.imsi = c[0] + tmp.imsi.substring(Math.min(c[0].length(), tmp.imsi.length()));
                        updateBtn.setText(t("运营商: ", "Carrier: ", "Оператор: ") + c[1]);
                    })
                    .setNegativeButton(t("取消", "Cancel", "Отмена"), null)
                    .show();
        } catch (Throwable e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ===== 设置页 =====
    private View buildSettingsPage() {
        float d = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(Math.round(24 * d), Math.round(48 * d), Math.round(24 * d), Math.round(32 * d));

        TextView title = new TextView(this);
        title.setText(t("设置", "Settings", "Настройки"));
        title.setTextSize(28);
        title.setTextColor(COLOR_WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        ll.addView(title);

        // 副标题
        TextView subtitle = new TextView(this);
        subtitle.setText(t("清除应用数据后自动生成全新设备识别码",
                "Auto-generate new device identity after clearing app data",
                "Автоматическая генерация новой идентификации устройства после очистки данных"));
        subtitle.setTextSize(14);
        subtitle.setTextColor(COLOR_GRAY);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Math.round(8 * d);
        ll.addView(subtitle, subLp);

        // 语言
        addSettingsSection(ll, t("语言", "Language", "Язык"), d);
        String langName = LANG_EN.equals(currentLang) ? "English" : LANG_ZH.equals(currentLang) ? "中文" : "Русский";
        Button btnLang = makeGlassBtn(t("语言: ", "Language: ", "Язык: ") + langName, 15);
        ll.addView(btnLang, makeFormLp(d));
        btnLang.setOnClickListener(v -> {
            if (LANG_EN.equals(currentLang)) currentLang = LANG_ZH;
            else if (LANG_ZH.equals(currentLang)) currentLang = LANG_RU;
            else currentLang = LANG_EN;
            getSharedPreferences("devicereset_ui", MODE_PRIVATE)
                    .edit().putString(PREFS_LANG, currentLang).apply();
            recreate();
        });

        // 检查更新
        Button btnUpdate = makeGlassBtn(t("检查更新", "Check Update", "Проверить обновления"), 15);
        ll.addView(btnUpdate, makeFormLp(d));
        btnUpdate.setOnClickListener(v -> checkUpdate(true));

        // 伪装选项
        addSettingsSection(ll, t("伪装选项", "Spoofing Options", "Параметры подмены"), d);
        String[] hookLabels = {
                t("伪装 Android ID", "Spoof Android ID", "Подмена Android ID"),
                t("伪装 广告ID", "Spoof Ad ID (AAID)", "Подмена рекламного ID"),
                t("伪装 IMEI/MEID", "Spoof IMEI/MEID", "Подмена IMEI/MEID"),
                t("伪装 设备型号", "Spoof Device Model", "Подмена модели устройства"),
                t("伪装 MAC地址", "Spoof MAC Address", "Подмена MAC-адреса"),
                t("伪装 GSF ID", "Spoof GSF ID", "Подмена GSF ID"),
                t("伪装 运营商信息", "Spoof Carrier Info", "Подмена информации оператора")
        };
        for (int i = 0; i < 7; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(16 * d), Math.round(12 * d), Math.round(16 * d), Math.round(12 * d));
            hookGlass[i] = new GlassButtonDrawable(24 * d, 1 * d, false);
            row.setBackground(hookGlass[i]);
            hookTv[i] = new TextView(this);
            hookTv[i].setText(hookLabels[i]);
            hookTv[i].setTextSize(15);
            hookTv[i].setTextColor(COLOR_WHITE);
            row.addView(hookTv[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final int idx = i;
            row.setOnClickListener(v -> toggleHook(idx));
            ll.addView(row, makeFormLp(d));
        }
        loadHookStates();

        // 维护
        addSettingsSection(ll, t("维护", "Maintenance", "Обслуживание"), d);
        Button btnReset = makeGlassBtn(t("手动重置身份", "Reset Identity (by package)", "Сбросить идентичность"), 15);
        ll.addView(btnReset, makeFormLp(d));
        btnReset.setOnClickListener(v -> showResetDialog());

        Button btnAbout = makeGlassBtn(t("关于", "About", "О программе"), 15);
        ll.addView(btnAbout, makeFormLp(d));
        btnAbout.setOnClickListener(v -> showAboutDialog());

        Button btnExportLog = makeGlassBtn(t("导出诊断日志", "Export Diagnostic Log", "Экспорт диагностического журнала"), 15);
        ll.addView(btnExportLog, makeFormLp(d));
        btnExportLog.setOnClickListener(v -> exportDiagnosticLog());

        // 说明文字（原首页内容）
        addSettingsSection(ll, t("使用方法", "Usage", "Использование"), d);
        addInfoBlock(ll, t("使用方法", "How to use", "Как использовать"),
                t("1. LSPosed管理器 → 模块 → 启用本模块 → 作用域勾选目标应用\n" +
                  "2. 重启手机（必须重启）\n" +
                  "3. 系统设置 → 应用 → 目标应用 → 存储 → 清除数据\n" +
                  "4. 重新打开应用，即获得全新设备身份\n" +
                  "5. 在「应用」页查看和自定义每个应用的身份",
                  "1. LSPosed Manager -> Modules -> Enable this module -> Check target apps in Scope\n" +
                  "2. Reboot phone (required)\n" +
                  "3. System Settings -> Apps -> Target app -> Storage -> Clear data\n" +
                  "4. Reopen the app to get a brand new device identity\n" +
                  "5. Use the Apps tab to view and customize per-app identities",
                  "1. LSPosed Manager -> Модули -> Включить модуль -> Отметить целевые приложения\n" +
                  "2. Перезагрузите телефон (обязательно)\n" +
                  "3. Настройки -> Приложения -> Целевое приложение -> Память -> Очистить данные\n" +
                  "4. Переоткройте приложение для получения новой идентичности\n" +
                  "5. Используйте вкладку «Приложения» для просмотра и настройки"), d);

        addInfoBlock(ll, t("工作原理", "How It Works", "Как это работает"),
                t("模块在目标应用私有目录放置隐藏哨兵文件。清除应用数据会删除整个私有目录，哨兵文件也被删除。下次应用启动时检测到哨兵不存在，即生成全新设备身份并写入新哨兵。",
                "The module places a hidden sentinel file in the target app's private directory. Clearing app data deletes the entire private directory, including the sentinel file. On next launch, the module detects the missing sentinel and generates a new device identity.",
                "Модуль помещает скрытый файл-sentinel в приватный каталог целевого приложения. Очистка данных удаляет весь приватный каталог. При следующем запуске модуль обнаруживает отсутствие sentinel и генерирует новую идентичность."), d);

        addInfoBlock(ll, t("伪装的识别码", "Spoofed Identifiers", "Подменяемые идентификаторы"),
                t("• Android ID (SSAID)\n• 广告ID (AAID) / AppSet ID\n• IMEI / MEID / IMSI / ICCID\n• 序列号 / MAC地址\n• GSF ID\n• 设备型号：品牌、型号、厂商、Build指纹\n• 运营商信息：代码、名称、国家",
                "• Android ID (SSAID)\n• Advertising ID (AAID) / AppSet ID\n• IMEI / MEID / IMSI / ICCID\n• Serial number / MAC address\n• GSF ID\n• Device: Brand, Model, Manufacturer, Build fingerprint\n• Carrier: Code, Name, Country",
                "• Android ID (SSAID)\n• Рекламный ID (AAID) / AppSet ID\n• IMEI / MEID / IMSI / ICCID\n• Серийный номер / MAC-адрес\n• GSF ID\n• Устройство: бренд, модель, производитель, отпечаток Build\n• Оператор: код, название, страна"), d);

        addInfoBlock(ll, t("注意事项", "Warnings", "Предупреждения"),
                t("• 本模块仅用于个人隐私保护和技术测试\n• 部分应用会检测Xposed/Root痕迹，存在账号封禁风险\n• 加壳应用请在LSPosed作用域设置中勾选「排除资源钩子」\n• native层直接读取系统属性的应用，Java层Hook无法拦截\n• 建议先在不重要的应用上测试\n• 排查问题：LSPosed → 日志 → 搜索「DeviceReset」",
                "• For personal privacy protection and technical testing only\n• Some apps detect Xposed/Root traces, account ban risk exists\n• For packed apps, enable \"Exclude resource hooks\" in LSPosed scope\n• Native-layer system property reads cannot be intercepted by Java hooks\n• Test on non-critical apps first\n• Troubleshooting: LSPosed -> Logs -> Search \"DeviceReset\"",
                "• Только для защиты личной конфиденциальности и технического тестирования\n• Некоторые приложения обнаруживают следы Xposed/Root, существует риск блокировки\n• Для упакованных приложений включите «Исключить хуки ресурсов»\n• Нативные чтения системных свойств не могут быть перехвачены Java-хуками\n• Сначала тестируйте на некритичных приложениях\n• Устранение неполадок: LSPosed -> Журналы -> Поиск «DeviceReset»"), d);

        sv.addView(ll);
        return sv;
    }

    private void addSettingsSection(LinearLayout ll, String text, float d) {
        TextView lbl = new TextView(this);
        lbl.setText(text);
        lbl.setTextSize(16);
        lbl.setTextColor(COLOR_WHITE);
        lbl.setTypeface(lbl.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(20 * d);
        lp.bottomMargin = Math.round(10 * d);
        ll.addView(lbl, lp);
    }

    private void addInfoBlock(LinearLayout ll, String title, String content, float d) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(COLOR_BLUE);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        ll.addView(t);
        TextView c = new TextView(this);
        c.setText(content);
        c.setTextSize(12);
        c.setTextColor(COLOR_GRAY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(12 * d);
        ll.addView(c, lp);
    }

    private void toggleHook(int idx) {
        boolean current = readHookState(idx);
        boolean next = !current;
        writeHookState(idx, next);
        hookTv[idx].setTextColor(next ? COLOR_BLUE : COLOR_WHITE);
        hookGlass[idx].setGlassSelected(next);
    }

    private boolean readHookState(int idx) {
        try {
            switch (idx) {
                case 0: return Config.isHookAndroidId(this);
                case 1: return Config.isHookAdId(this);
                case 2: return Config.isHookImei(this);
                case 3: return Config.isHookBuild(this);
                case 4: return Config.isHookMac(this);
                case 5: return Config.isHookGsf(this);
                case 6: return Config.isHookCarrier(this);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void writeHookState(int idx, boolean val) {
        try {
            switch (idx) {
                case 0: Config.setHookAndroidId(this, val); break;
                case 1: Config.setHookAdId(this, val); break;
                case 2: Config.setHookImei(this, val); break;
                case 3: Config.setHookBuild(this, val); break;
                case 4: Config.setHookMac(this, val); break;
                case 5: Config.setHookGsf(this, val); break;
                case 6: Config.setHookCarrier(this, val); break;
            }
        } catch (Throwable ignored) {}
    }

    private void loadHookStates() {
        for (int i = 0; i < 7; i++) {
            boolean state = readHookState(i);
            hookTv[i].setTextColor(state ? COLOR_BLUE : COLOR_WHITE);
            hookGlass[i].setGlassSelected(state);
        }
    }

    // ===== Root 工具 =====
    private java.util.List<String> scanPackagesWithIdentity() {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        try {
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            // 多用户路径 + 传统路径 + 外部存储
            os.writeBytes("for u in /data/user/*/; do for d in \"$u\"*/; do pkg=$(basename \"$d\"); if [ -f \"$d/files/.identity_sentinel\" ]; then echo \"$pkg\"; fi; done; done\n");
            os.writeBytes("for d in /data/data/*/; do pkg=$(basename \"$d\"); if [ -f \"$d/files/.identity_sentinel\" ]; then echo \"$pkg\"; fi; done\n");
            os.writeBytes("for d in /sdcard/Android/data/*/; do pkg=$(basename \"$d\"); if [ -f \"$d/files/.identity_sentinel\" ]; then echo \"$pkg\"; fi; done\n");
            os.writeBytes("exit\n");
            os.flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(su.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.equals("*") && line.contains(".")) result.add(line);
            }
            reader.close();
            su.waitFor();
        } catch (Throwable ignored) {}
        return new java.util.ArrayList<>(result);
    }

    private String readIdentityFile(String packageName) {
        // 先尝试内部路径，再尝试外部路径
        String[] paths = {
            "/data/data/" + packageName + "/files/.identity_sentinel",
            "/sdcard/Android/data/" + packageName + "/files/.identity_sentinel"
        };
        for (String path : paths) {
            try {
                Process su = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                os.writeBytes("cat '" + path + "' 2>/dev/null\n");
                os.writeBytes("exit\n");
                os.flush();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(su.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                int rc = su.waitFor();
                String output = sb.toString().trim();
                if (rc == 0 && !output.isEmpty() && output.startsWith("{")) return output;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void writeIdentityFile(String packageName, String json) {
        try {
            String path = "/data/data/" + packageName + "/files/.identity_sentinel";
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("echo '" + json.replace("'", "'\\''") + "' > '" + path + "'\n");
            os.writeBytes("chmod 600 '" + path + "'\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();
        } catch (Throwable ignored) {}
    }

    // ===== 导出诊断日志 =====
    private void exportDiagnosticLog() {
        Toast.makeText(this, t("正在收集诊断信息...", "Collecting diagnostic info...", "Сбор диагностической информации..."), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                java.io.File tmpDir = new java.io.File(getCacheDir(), "drs_log_" + System.currentTimeMillis());
                tmpDir.mkdirs();

                // 1. 设备信息
                StringBuilder devInfo = new StringBuilder();
                devInfo.append("=== Device Info ===\n");
                devInfo.append("Brand: ").append(android.os.Build.BRAND).append("\n");
                devInfo.append("Model: ").append(android.os.Build.MODEL).append("\n");
                devInfo.append("Manufacturer: ").append(android.os.Build.MANUFACTURER).append("\n");
                devInfo.append("Device: ").append(android.os.Build.DEVICE).append("\n");
                devInfo.append("Product: ").append(android.os.Build.PRODUCT).append("\n");
                devInfo.append("Hardware: ").append(android.os.Build.HARDWARE).append("\n");
                devInfo.append("Fingerprint: ").append(android.os.Build.FINGERPRINT).append("\n");
                devInfo.append("Build ID: ").append(android.os.Build.ID).append("\n");
                devInfo.append("Android Version: ").append(android.os.Build.VERSION.RELEASE).append("\n");
                devInfo.append("SDK Level: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
                devInfo.append("Module Version: 3.0.0 (versionCode 44)\n");
                devInfo.append("Language: ").append(currentLang).append("\n");
                // Root 状态
                devInfo.append("\n=== Root Status ===\n");
                try {
                    Process su = Runtime.getRuntime().exec("su");
                    java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                    os.writeBytes("id\n");
                    os.writeBytes("exit\n");
                    os.flush();
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(su.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) devInfo.append("su output: ").append(l).append("\n");
                    r.close();
                    su.waitFor();
                    devInfo.append("Root: GRANTED\n");
                } catch (Throwable e) {
                    devInfo.append("Root: FAILED - ").append(e.getMessage()).append("\n");
                }
                writeFile(new java.io.File(tmpDir, "device_info.txt"), devInfo.toString());

                // 2. 模块配置
                StringBuilder cfg = new StringBuilder();
                cfg.append("=== Module Config ===\n");
                cfg.append("hook_android_id: ").append(Config.isHookAndroidId(this)).append("\n");
                cfg.append("hook_ad_id: ").append(Config.isHookAdId(this)).append("\n");
                cfg.append("hook_imei: ").append(Config.isHookImei(this)).append("\n");
                cfg.append("hook_build_info: ").append(Config.isHookBuild(this)).append("\n");
                cfg.append("hook_mac: ").append(Config.isHookMac(this)).append("\n");
                cfg.append("hook_gsf_id: ").append(Config.isHookGsf(this)).append("\n");
                cfg.append("hook_carrier: ").append(Config.isHookCarrier(this)).append("\n");
                cfg.append("auto_reset: ").append(Config.isAutoReset(this)).append("\n");
                cfg.append("\n=== Target Packages (from prefs) ===\n");
                for (String p : Config.getTargetPackages(this)) cfg.append(p).append("\n");
                writeFile(new java.io.File(tmpDir, "module_config.txt"), cfg.toString());

                // 3. 扫描所有应用的哨兵文件状态
                StringBuilder scan = new StringBuilder();
                scan.append("=== Sentinel File Scan ===\n");
                scan.append("Scanning /data/data/*/files/.identity_sentinel and /sdcard/Android/data/*/files/.identity_sentinel\n\n");
                try {
                    Process su = Runtime.getRuntime().exec("su");
                    java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                    os.writeBytes("echo '--- INTERNAL (/data/data) ---'\n");
                    os.writeBytes("for d in /data/data/*/; do pkg=$(basename \"$d\"); f=\"$d/files/.identity_sentinel\"; if [ -f \"$f\" ]; then echo \"FOUND: $pkg ($(wc -c < \"$f\") bytes)\"; fi; done\n");
                    os.writeBytes("echo '--- EXTERNAL (/sdcard/Android/data) ---'\n");
                    os.writeBytes("for d in /sdcard/Android/data/*/; do pkg=$(basename \"$d\"); f=\"$d/files/.identity_sentinel\"; if [ -f \"$f\" ]; then echo \"FOUND: $pkg ($(wc -c < \"$f\") bytes)\"; fi; done\n");
                    os.writeBytes("echo '--- RUNTIME FILES ---'\n");
                    os.writeBytes("for d in /sdcard/Android/data/*/; do pkg=$(basename \"$d\"); f=\"$d/files/.identity_runtime\"; if [ -f \"$f\" ]; then echo \"RUNTIME: $pkg\"; fi; done\n");
                    os.writeBytes("exit\n");
                    os.flush();
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(su.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) scan.append(l).append("\n");
                    r.close();
                    su.waitFor();
                } catch (Throwable e) {
                    scan.append("Scan error: ").append(e.getMessage()).append("\n");
                }
                writeFile(new java.io.File(tmpDir, "scan_result.txt"), scan.toString());

                // 4. 导出所有有哨兵文件的应用的身份
                java.io.File idDir = new java.io.File(tmpDir, "identities");
                idDir.mkdirs();
                java.util.List<String> pkgs = scanPackagesWithIdentity();
                for (String pkg : pkgs) {
                    String json = readIdentityFile(pkg);
                    if (json != null) {
                        writeFile(new java.io.File(idDir, pkg + ".json"), json);
                    }
                }

                // 5. LSPosed 模块状态（尝试读取）
                StringBuilder lsp = new StringBuilder();
                lsp.append("=== LSPosed Status ===\n");
                lsp.append("Module package: io.github.gjr787878.devicereset\n");
                lsp.append("Note: If no apps found with sentinel files, the module may not be enabled in LSPosed Manager.\n");
                lsp.append("Please check: LSPosed Manager -> Modules -> DeviceResetSpooferX -> Enabled -> Scope -> select target apps -> Reboot\n");
                try {
                    Process su = Runtime.getRuntime().exec("su");
                    java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
                    os.writeBytes("ls -la /data/adb/lspd/ 2>/dev/null\n");
                    os.writeBytes("cat /data/adb/lspd/config/modules_config.json 2>/dev/null | head -100\n");
                    os.writeBytes("exit\n");
                    os.flush();
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(su.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) lsp.append(l).append("\n");
                    r.close();
                    su.waitFor();
                } catch (Throwable e) {
                    lsp.append("LSPosed config read error: ").append(e.getMessage()).append("\n");
                }
                writeFile(new java.io.File(tmpDir, "lsposed_status.txt"), lsp.toString());

                // 6. 打包成 zip
                java.io.File zipFile = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "log.zip");
                zipDirectory(tmpDir, zipFile);

                // 清理临时目录
                deleteRecursive(tmpDir);

                final String zipPath = zipFile.getAbsolutePath();
                runOnUiThread(() -> Toast.makeText(this,
                        t("日志已导出: ", "Log exported: ", "Журнал экспортирован: ") + zipPath,
                        Toast.LENGTH_LONG).show());
            } catch (Throwable e) {
                final String err = e.getMessage();
                runOnUiThread(() -> Toast.makeText(this,
                        t("导出失败: ", "Export failed: ", "Ошибка экспорта: ") + err,
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void writeFile(java.io.File f, String content) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(content);
            w.close();
        } catch (Throwable ignored) {}
    }

    private void zipDirectory(java.io.File srcDir, java.io.File zipFile) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(zipFile);
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos);
        java.io.File[] files = srcDir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isDirectory()) {
                    java.io.File[] subFiles = f.listFiles();
                    if (subFiles != null) {
                        for (java.io.File sf : subFiles) {
                            addToZip(zos, sf, f.getName() + "/");
                        }
                    }
                } else {
                    addToZip(zos, f, "");
                }
            }
        }
        zos.close();
        fos.close();
    }

    private void addToZip(java.util.zip.ZipOutputStream zos, java.io.File file, String prefix) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(prefix + file.getName());
        zos.putNextEntry(entry);
        byte[] buffer = new byte[4096];
        int len;
        while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
        fis.close();
        zos.closeEntry();
    }

    private void deleteRecursive(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) for (java.io.File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    // ===== 对话框 =====
    private void showResetDialog() {
        final EditText input = new EditText(this);
        input.setHint(t("输入应用包名，如 com.example.app", "Enter package name, e.g. com.example.app", "Введите имя пакета, например com.example.app"));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);
        layout.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(t("手动重置身份", "Reset Identity", "Сбросить идентичность"))
                .setMessage(t("输入要重置身份的应用包名。重置后该应用下次启动将获得全新设备身份（无需清除数据）。",
                        "Enter the package name to reset. Next launch will get a new identity (no need to clear data).",
                        "Введите имя пакета для сброса. При следующем запуске будет получена новая идентичность."))
                .setView(layout)
                .setPositiveButton(t("重置", "Reset", "Сбросить"), (d, w) -> {
                    String pkg = input.getText().toString().trim();
                    if (pkg.isEmpty()) { Toast.makeText(this, t("请输入包名", "Please enter package name", "Введите имя пакета"), Toast.LENGTH_SHORT).show(); return; }
                    try {
                        boolean ok = SentinelDetector.resetIdentity(pkg, this);
                        Toast.makeText(this, ok ? t("已重置 ", "Reset ", "Сброшена ") + pkg : t("重置失败，请确保已授予ROOT权限", "Reset failed, ensure ROOT access", "Сброс не удался, убедитесь в наличии Root-прав"), Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        Toast.makeText(this, t("错误: ", "Error: ", "Ошибка: ") + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(t("取消", "Cancel", "Отмена"), null)
                .show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("DeviceResetSpooferX")
                .setMessage(t("版本：3.0.0\n\n清除应用数据后自动生成全新设备识别码的LSPosed模块。\n\n直接对LSPosed作用域中勾选的应用生效。\n支持中文 / English / Русский",
                        "Version: 3.0.0\n\nLSPosed module that auto-generates new device identity after clearing app data.\n\nApplies to all apps checked in LSPosed scope.\nSupports Chinese / English / Russian",
                        "Версия: 3.0.0\n\nМодуль LSPosed, автоматически генерирующий новую идентификацию устройства.\n\nПрименяется ко всем приложениям, отмеченным в области LSPosed.\nПоддерживает 中文 / English / Русский"))
                .setPositiveButton(t("确定", "OK", "ОК"), null)
                .show();
    }

    // ===== 更新检测（保留原项目逻辑） =====
    private void checkUpdate(boolean manual) {
        String versionName;
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) { versionName = "0"; }
        UpdateChecker.check(UPDATE_REPO, versionName,
                (latest, tag, hasUpdate, error) -> runOnUiThread(() -> {
                    if (hasUpdate) {
                        showUpdateDialog(latest, tag);
                    } else if (manual) {
                        Toast.makeText(this, error != null ?
                                t("检查更新失败: ", "Update check failed: ", "Не удалось проверить обновления: ") + error :
                                t("已是最新版本", "You are up to date", "У вас последняя версия"),
                                Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    private void showUpdateDialog(String latest, String tag) {
        new AlertDialog.Builder(this)
                .setTitle(t("发现新版本 v", "New version v", "Доступна новая версия v") + latest)
                .setMessage(t("检测到新版本，是否下载？", "New version detected. Download?", "Обнаружена новая версия. Скачать?"))
                .setPositiveButton(t("下载", "Download", "Скачать"), (d, w) -> {
                    String asset = "DeviceResetSpooferX-v" + latest + ".apk";
                    String direct = "https://github.com/" + UPDATE_REPO + "/releases/download/" + tag + "/" + asset;
                    String[] mirrors = {"https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/",
                            "https://mirror.ghproxy.com/", "https://github.moeyy.xyz/"};
                    String[] urls = new String[mirrors.length + 1];
                    for (int i = 0; i < mirrors.length; i++) urls[i] = mirrors[i] + direct;
                    urls[mirrors.length] = direct;
                    AppDownloader.start(this, urls, REPO_HOME_URL,
                            "DeviceResetSpooferX-v" + latest + ".apk", latest, currentLang);
                })
                .setNegativeButton(t("取消", "Cancel", "Отмена"), null)
                .show();
    }
}
