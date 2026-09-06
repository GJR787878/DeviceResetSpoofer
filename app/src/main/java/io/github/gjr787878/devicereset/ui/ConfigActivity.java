package io.github.gjr787878.devicereset.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import io.github.gjr787878.devicereset.Config;
import io.github.gjr787878.devicereset.GlassButtonDrawable;
import io.github.gjr787878.devicereset.R;
import io.github.gjr787878.devicereset.xposed.Identity;
import io.github.gjr787878.devicereset.xposed.SentinelDetector;

public class ConfigActivity extends AppCompatActivity {
    private static final String PREFS_LANG = "app_language";
    private static final String LANG_ZH = "zh";
    private static final String LANG_EN = "en";
    private static final String LANG_RU = "ru";

    private String currentLang;
    private LinearLayout btnAndroidId;
    private LinearLayout btnAdId;
    private LinearLayout btnImei;
    private LinearLayout btnBuild;
    private LinearLayout btnMac;
    private LinearLayout btnGsf;
    private LinearLayout btnCarrier;
    private GlassButtonDrawable glassAndroidId;
    private GlassButtonDrawable glassAdId;
    private GlassButtonDrawable glassImei;
    private GlassButtonDrawable glassBuild;
    private GlassButtonDrawable glassMac;
    private GlassButtonDrawable glassGsf;
    private GlassButtonDrawable glassCarrier;
    private TextView tvAndroidId;
    private TextView tvAdId;
    private TextView tvImei;
    private TextView tvBuild;
    private TextView tvMac;
    private TextView tvGsf;
    private TextView tvCarrier;

    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_BLUE = 0xFF0A84FF;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            SharedPreferences prefs = getSharedPreferences("devicereset_ui", MODE_PRIVATE);
            currentLang = prefs.getString(PREFS_LANG, LANG_ZH);

            setContentView(R.layout.activity_config);
            updateLanguage();
            initViews();
            loadConfig();
        } catch (Throwable t) {
            Toast.makeText(this, "初始化失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateLanguage() {
        boolean isZh = LANG_ZH.equals(currentLang);
        boolean isEn = LANG_EN.equals(currentLang);

        if (isZh) {
            setTitle("应用配置");
        } else if (isEn) {
            setTitle("Config");
        } else {
            setTitle("Настройки");
        }

        TextView tvScopeTitle = findViewById(R.id.tv_scope_title);
        TextView tvScopeContent = findViewById(R.id.tv_scope_content);
        TextView tvSpoofTitle = findViewById(R.id.tv_spoof_title);
        tvAndroidId = findViewById(R.id.tv_android_id);
        tvAdId = findViewById(R.id.tv_ad_id);
        tvImei = findViewById(R.id.tv_imei);
        tvBuild = findViewById(R.id.tv_build);
        tvMac = findViewById(R.id.tv_mac);
        tvGsf = findViewById(R.id.tv_gsf);
        tvCarrier = findViewById(R.id.tv_carrier);
        TextView tvShowIdentity = findViewById(R.id.tv_show_identity);
        TextView tvFooter = findViewById(R.id.tv_footer);

        if (isZh) {
            tvScopeTitle.setText("ℹ️ 生效范围");
            tvScopeContent.setText("本模块直接对 LSPosed 作用域中勾选的应用生效，无需在此再次选择。\n\n在 LSPosed 管理器中勾选目标应用 → 重启手机 → 清除目标应用数据 → 重新打开即获得新身份。");
            tvSpoofTitle.setText("🎭 伪装选项");
            tvAndroidId.setText("伪装 Android ID");
            tvAdId.setText("伪装 广告ID (AAID)");
            tvImei.setText("伪装 IMEI/MEID");
            tvBuild.setText("伪装 设备型号 (Build)");
            tvMac.setText("伪装 MAC地址");
            tvGsf.setText("伪装 GSF ID");
            tvCarrier.setText("伪装 运营商信息");
            tvShowIdentity.setText("显示当前伪装值");
            tvFooter.setText("右上角菜单可手动重置身份。\n排查问题：LSPosed → 日志 → 搜索「DeviceReset」");
        } else if (isEn) {
            tvScopeTitle.setText("ℹ️ Scope");
            tvScopeContent.setText("This module applies to all apps checked in LSPosed scope. No need to select again here.\n\nCheck target apps in LSPosed Manager → Reboot → Clear target app data → Reopen to get new identity.");
            tvSpoofTitle.setText("🎭 Spoofing Options");
            tvAndroidId.setText("Spoof Android ID");
            tvAdId.setText("Spoof Advertising ID (AAID)");
            tvImei.setText("Spoof IMEI/MEID");
            tvBuild.setText("Spoof Device Model (Build)");
            tvMac.setText("Spoof MAC Address");
            tvGsf.setText("Spoof GSF ID");
            tvCarrier.setText("Spoof Carrier Info");
            tvShowIdentity.setText("View Current Identity");
            tvFooter.setText("Manually reset identity from top-right menu.\nTroubleshooting: LSPosed → Logs → Search \"DeviceReset\"");
        } else {
            tvScopeTitle.setText("ℹ️ Область");
            tvScopeContent.setText("Модуль применяется ко всем приложениям, отмеченным в области LSPosed. Не нужно выбирать снова здесь.\n\nОтметьте целевые приложения в LSPosed Manager → Перезагрузка → Очистить данные целевого приложения → Переоткройте для получения новой идентичности.");
            tvSpoofTitle.setText("🎭 Параметры подмены");
            tvAndroidId.setText("Подмена Android ID");
            tvAdId.setText("Подмена рекламного ID (AAID)");
            tvImei.setText("Подмена IMEI/MEID");
            tvBuild.setText("Подмена модели устройства (Build)");
            tvMac.setText("Подмена MAC-адрес");
            tvGsf.setText("Подмена GSF ID");
            tvCarrier.setText("Подмена информации об операторе");
            tvShowIdentity.setText("Просмотр текущей подмены");
            tvFooter.setText("Ручной сброс идентичности из меню в правом верхнем углу.\nУстранение неполадок: LSPosed → Журналы → Поиск «DeviceReset»");
        }
    }

    private void initViews() {
        btnAndroidId = findViewById(R.id.btn_android_id);
        btnAdId = findViewById(R.id.btn_ad_id);
        btnImei = findViewById(R.id.btn_imei);
        btnBuild = findViewById(R.id.btn_build);
        btnMac = findViewById(R.id.btn_mac);
        btnGsf = findViewById(R.id.btn_gsf);
        btnCarrier = findViewById(R.id.btn_carrier);

        float radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        float borderPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());
        glassAndroidId = new GlassButtonDrawable(radiusPx, borderPx, false);
        glassAdId = new GlassButtonDrawable(radiusPx, borderPx, false);
        glassImei = new GlassButtonDrawable(radiusPx, borderPx, false);
        glassBuild = new GlassButtonDrawable(radiusPx, borderPx, false);
        glassMac = new GlassButtonDrawable(radiusPx, borderPx, false);
        glassGsf = new GlassButtonDrawable(radiusPx, borderPx, false);
        glassCarrier = new GlassButtonDrawable(radiusPx, borderPx, false);
        btnAndroidId.setBackground(glassAndroidId);
        btnAdId.setBackground(glassAdId);
        btnImei.setBackground(glassImei);
        btnBuild.setBackground(glassBuild);
        btnMac.setBackground(glassMac);
        btnGsf.setBackground(glassGsf);
        btnCarrier.setBackground(glassCarrier);

        btnAndroidId.setOnClickListener(v -> toggleHook(0));
        btnAdId.setOnClickListener(v -> toggleHook(1));
        btnImei.setOnClickListener(v -> toggleHook(2));
        btnBuild.setOnClickListener(v -> toggleHook(3));
        btnMac.setOnClickListener(v -> toggleHook(4));
        btnGsf.setOnClickListener(v -> toggleHook(5));
        btnCarrier.setOnClickListener(v -> toggleHook(6));

        // 显示当前伪装值按钮
        LinearLayout btnShowIdentity = findViewById(R.id.btn_show_identity);
        GlassButtonDrawable glassShowIdentity = new GlassButtonDrawable(radiusPx, borderPx, false);
        btnShowIdentity.setBackground(glassShowIdentity);
        btnShowIdentity.setOnClickListener(v ->
                startActivity(new Intent(this, AppPickerActivity.class)));
    }

    private GlassButtonDrawable getGlassDrawable(int index) {
        switch (index) {
            case 0: return glassAndroidId;
            case 1: return glassAdId;
            case 2: return glassImei;
            case 3: return glassBuild;
            case 4: return glassMac;
            case 5: return glassGsf;
            case 6: return glassCarrier;
        }
        return null;
    }

    /** 切换某个伪装选项的状态，index: 0=AndroidId 1=AdId 2=Imei 3=Build 4=Mac 5=Gsf 6=Carrier */
    private void toggleHook(int index) {
        boolean current = readHookState(index);
        boolean next = !current;
        writeHookState(index, next);
        TextView tv = getHookTextView(index);
        updateButtonTextColor(tv, next);
        GlassButtonDrawable glass = getGlassDrawable(index);
        if (glass != null) glass.setGlassSelected(next);
    }

    private boolean readHookState(int index) {
        try {
            switch (index) {
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

    private void writeHookState(int index, boolean value) {
        try {
            switch (index) {
                case 0: Config.setHookAndroidId(this, value); break;
                case 1: Config.setHookAdId(this, value); break;
                case 2: Config.setHookImei(this, value); break;
                case 3: Config.setHookBuild(this, value); break;
                case 4: Config.setHookMac(this, value); break;
                case 5: Config.setHookGsf(this, value); break;
                case 6: Config.setHookCarrier(this, value); break;
            }
        } catch (Throwable ignored) {}
    }

    private TextView getHookTextView(int index) {
        switch (index) {
            case 0: return tvAndroidId;
            case 1: return tvAdId;
            case 2: return tvImei;
            case 3: return tvBuild;
            case 4: return tvMac;
            case 5: return tvGsf;
            case 6: return tvCarrier;
        }
        return null;
    }

    /** 选中时文字变蓝，未选中时白色 */
    private void updateButtonTextColor(TextView tv, boolean checked) {
        if (tv != null) {
            tv.setTextColor(checked ? COLOR_BLUE : COLOR_WHITE);
        }
    }

    private void loadConfig() {
        for (int i = 0; i < 7; i++) {
            boolean state = readHookState(i);
            updateButtonTextColor(getHookTextView(i), state);
            GlassButtonDrawable glass = getGlassDrawable(i);
            if (glass != null) glass.setGlassSelected(state);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean isZh = LANG_ZH.equals(currentLang);
        boolean isEn = LANG_EN.equals(currentLang);
        if (isZh) {
            menu.add(0, 1, 0, "手动重置身份");
            menu.add(0, 2, 1, "关于");
        } else if (isEn) {
            menu.add(0, 1, 0, "Reset Identity");
            menu.add(0, 2, 1, "About");
        } else {
            menu.add(0, 1, 0, "Сбросить идентичность");
            menu.add(0, 2, 1, "О программе");
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            showResetDialog();
            return true;
        } else if (item.getItemId() == 2) {
            showAboutDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ==================== 显示当前伪装值 ====================
    private void showAppPickerDialog() {
        boolean isZh = LANG_ZH.equals(currentLang);
        boolean isEn = LANG_EN.equals(currentLang);
        String loadingTitle = isZh ? "正在扫描" : isEn ? "Scanning" : "Сканирование";
        String loadingMsg = isZh ? "正在扫描有伪装值的应用..." : isEn ? "Scanning apps with spoofed identity..." : "Сканирование приложений с подменённой идентичностью...";

        AlertDialog loading = new AlertDialog.Builder(this)
                .setTitle(loadingTitle)
                .setMessage(loadingMsg)
                .setCancelable(false)
                .show();

        new Thread(() -> {
            // 一条 root 命令列出所有有哨兵文件的包名
            final java.util.List<String> pkgs = scanPackagesWithIdentity();
            runOnUiThread(() -> {
                loading.dismiss();
                if (pkgs.isEmpty()) {
                    String emptyMsg = isZh ? "未找到有伪装值的应用。\n请确保目标应用已在 LSPosed 作用域中勾选并至少运行过一次。"
                            : isEn ? "No apps with spoofed identity found.\nEnsure target apps are checked in LSPosed scope and launched at least once."
                            : "Не найдено приложений с подменённой идентичностью.\nУбедитесь, что целевые приложения отмечены в области LSPosed и запускались хотя бы раз.";
                    new AlertDialog.Builder(this)
                            .setTitle(isZh ? "提示" : isEn ? "Notice" : "Уведомление")
                            .setMessage(emptyMsg)
                            .setPositiveButton(isZh ? "确定" : "OK", null)
                            .show();
                    return;
                }

                // 匹配应用名
                PackageManager pm = getPackageManager();
                final String[] displayNames = new String[pkgs.size()];
                for (int i = 0; i < pkgs.size(); i++) {
                    String pkg = pkgs.get(i);
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        String name = pm.getApplicationLabel(ai).toString();
                        displayNames[i] = name + "\n" + pkg;
                    } catch (Throwable e) {
                        displayNames[i] = pkg;
                    }
                }

                String title = isZh ? "选择应用" : isEn ? "Select App" : "Выберите приложение";
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setItems(displayNames, (dialog, which) -> {
                            String pkg = pkgs.get(which);
                            String json = readIdentityFile(pkg);
                            if (json != null) {
                                showIdentityDialog(pkg, json);
                            } else {
                                String failMsg = isZh ? "读取失败" : isEn ? "Read failed" : "Ошибка чтения";
                                Toast.makeText(this, failMsg, Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(isZh ? "取消" : isEn ? "Cancel" : "Отмена", null)
                        .show();
            });
        }).start();
    }

    /** 通过 Root 扫描所有有 .identity_sentinel 文件的应用包名 */
    private java.util.List<String> scanPackagesWithIdentity() {
        java.util.List<String> result = new java.util.ArrayList<>();
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
                if (!line.isEmpty()) {
                    result.add(line);
                }
            }
            reader.close();
            su.waitFor();
        } catch (Throwable ignored) {}
        return result;
    }

    /** 通过 Root 读取目标应用的哨兵文件 */
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
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            int result = su.waitFor();
            String output = sb.toString().trim();
            if (result == 0 && !output.isEmpty() && output.startsWith("{")) {
                return output;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析并显示伪装值 */
    private void showIdentityDialog(String packageName, String json) {
        Identity id = Identity.fromJson(json);
        if (id == null) {
            Toast.makeText(this, "解析失败", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean isZh = LANG_ZH.equals(currentLang);
        StringBuilder sb = new StringBuilder();
        sb.append(isZh ? "应用包名: " : "Package: ").append(packageName).append("\n\n");

        if (id.androidId != null) sb.append(isZh ? "Android ID: " : "Android ID: ").append(id.androidId).append("\n");
        if (id.advertisingId != null) sb.append(isZh ? "广告ID (AAID): " : "Ad ID (AAID): ").append(id.advertisingId).append("\n");
        if (id.appSetId != null) sb.append(isZh ? "AppSet ID: " : "AppSet ID: ").append(id.appSetId).append("\n");
        if (id.imei != null) sb.append(isZh ? "IMEI: " : "IMEI: ").append(id.imei).append("\n");
        if (id.meid != null) sb.append(isZh ? "MEID: " : "MEID: ").append(id.meid).append("\n");
        if (id.serial != null) sb.append(isZh ? "序列号: " : "Serial: ").append(id.serial).append("\n");
        if (id.macAddress != null) sb.append(isZh ? "MAC地址: " : "MAC: ").append(id.macAddress).append("\n");
        if (id.gsfId != null) sb.append(isZh ? "GSF ID: " : "GSF ID: ").append(id.gsfId).append("\n");

        sb.append("\n");
        if (id.brand != null) sb.append(isZh ? "品牌: " : "Brand: ").append(id.brand).append("\n");
        if (id.model != null) sb.append(isZh ? "型号: " : "Model: ").append(id.model).append("\n");
        if (id.manufacturer != null) sb.append(isZh ? "厂商: " : "Manufacturer: ").append(id.manufacturer).append("\n");
        if (id.device != null) sb.append(isZh ? "设备: " : "Device: ").append(id.device).append("\n");
        if (id.product != null) sb.append(isZh ? "产品: " : "Product: ").append(id.product).append("\n");
        if (id.hardware != null) sb.append(isZh ? "硬件: " : "Hardware: ").append(id.hardware).append("\n");
        if (id.fingerprint != null) sb.append(isZh ? "指纹: " : "Fingerprint: ").append(id.fingerprint).append("\n");
        if (id.buildId != null) sb.append(isZh ? "Build ID: " : "Build ID: ").append(id.buildId).append("\n");
        if (id.bootloader != null) sb.append(isZh ? "Bootloader: " : "Bootloader: ").append(id.bootloader).append("\n");

        sb.append("\n");
        if (id.networkOperator != null) sb.append(isZh ? "网络运营商代码: " : "Network Op: ").append(id.networkOperator).append("\n");
        if (id.networkOperatorName != null) sb.append(isZh ? "网络运营商: " : "Network Op Name: ").append(id.networkOperatorName).append("\n");
        if (id.simOperator != null) sb.append(isZh ? "SIM运营商代码: " : "SIM Op: ").append(id.simOperator).append("\n");
        if (id.simOperatorName != null) sb.append(isZh ? "SIM运营商: " : "SIM Op Name: ").append(id.simOperatorName).append("\n");
        if (id.simCountryIso != null) sb.append(isZh ? "SIM国家: " : "SIM Country: ").append(id.simCountryIso).append("\n");

        String title = isZh ? "当前伪装值" : "Current Spoofed Values";
        String ok = isZh ? "确定" : "OK";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(sb.toString())
                .setPositiveButton(ok, null)
                .show();
    }

    private void showResetDialog() {
        boolean isZh = LANG_ZH.equals(currentLang);
        boolean isEn = LANG_EN.equals(currentLang);
        final EditText input = new EditText(this);
        if (isZh) {
            input.setHint("输入应用包名，如 com.example.app");
        } else if (isEn) {
            input.setHint("Enter package name, e.g. com.example.app");
        } else {
            input.setHint("Введите имя пакета, например com.example.app");
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);
        layout.addView(input);

        String title, message, positive, negative, emptyMsg, successPrefix, successSuffix, failMsg, errorPrefix;
        if (isZh) {
            title = "手动重置身份";
            message = "输入要重置身份的应用包名。重置后该应用下次启动将获得全新设备身份（无需清除数据）。";
            positive = "重置";
            negative = "取消";
            emptyMsg = "请输入包名";
            successPrefix = "已重置 ";
            successSuffix = " 的身份";
            failMsg = "重置失败，请确保已授予ROOT权限";
            errorPrefix = "重置异常：";
        } else if (isEn) {
            title = "Reset Identity";
            message = "Enter the package name of the app to reset. Next launch will get a brand new device identity (no need to clear data).";
            positive = "Reset";
            negative = "Cancel";
            emptyMsg = "Please enter package name";
            successPrefix = "Reset ";
            successSuffix = " identity";
            failMsg = "Reset failed, please ensure ROOT access";
            errorPrefix = "Error: ";
        } else {
            title = "Сбросить идентичность";
            message = "Введите имя пакета приложения для сброса. При следующем запуске будет получена новая идентификация устройства (без очистки данных).";
            positive = "Сбросить";
            negative = "Отмена";
            emptyMsg = "Введите имя пакета";
            successPrefix = "Сброшена идентичность ";
            successSuffix = "";
            failMsg = "Сброс не удался, убедитесь в наличии Root-прав";
            errorPrefix = "Ошибка: ";
        }

        final String fEmptyMsg = emptyMsg;
        final String fSuccessPrefix = successPrefix;
        final String fSuccessSuffix = successSuffix;
        final String fFailMsg = failMsg;
        final String fErrorPrefix = errorPrefix;

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(layout)
                .setPositiveButton(positive, (dialog, which) -> {
                    String pkg = input.getText().toString().trim();
                    if (pkg.isEmpty()) {
                        Toast.makeText(this, fEmptyMsg, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        boolean success = SentinelDetector.resetIdentity(pkg, this);
                        if (success) {
                            Toast.makeText(this, fSuccessPrefix + pkg + fSuccessSuffix, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, fFailMsg, Toast.LENGTH_LONG).show();
                        }
                    } catch (Throwable t) {
                        Toast.makeText(this, fErrorPrefix + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(negative, null)
                .show();
    }

    private void showAboutDialog() {
        boolean isZh = LANG_ZH.equals(currentLang);
        boolean isEn = LANG_EN.equals(currentLang);
        String message, positive;
        if (isZh) {
            message = "版本：1.0.4\n\n清除应用数据后自动生成全新设备识别码的LSPosed模块。\n\n直接对LSPosed作用域中勾选的应用生效。\n支持中文 / English / Русский";
            positive = "确定";
        } else if (isEn) {
            message = "Version: 1.0.4\n\nLSPosed module that auto-generates new device identity after clearing app data.\n\nApplies to all apps checked in LSPosed scope.\nSupports 中文 / English / Русский";
            positive = "OK";
        } else {
            message = "Версия: 1.0.4\n\nМодуль LSPosed, автоматически генерирующий новую идентификацию устройства после очистки данных приложения.\n\nПрименяется ко всем приложениям, отмеченным в области LSPosed.\nПоддерживает 中文 / English / Русский";
            positive = "ОК";
        }
        new AlertDialog.Builder(this)
                .setTitle("DeviceResetSpoofer")
                .setMessage(message)
                .setPositiveButton(positive, null)
                .show();
    }
}
