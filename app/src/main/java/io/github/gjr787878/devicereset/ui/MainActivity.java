package io.github.gjr787878.devicereset.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import io.github.gjr787878.devicereset.GlassButtonDrawable;
import io.github.gjr787878.devicereset.R;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_LANG = "app_language";
    private static final String LANG_ZH = "zh";
    private static final String LANG_EN = "en";
    private static final String LANG_RU = "ru";

    // 更新检测：GitHub 仓库与发布页
    private static final String UPDATE_REPO = "GJR787878/DeviceResetSpoofer";
    private static final String RELEASES_URL = "https://github.com/GJR787878/DeviceResetSpoofer/releases/latest";
    private static final String REPO_HOME_URL = "https://github.com/GJR787878/DeviceResetSpoofer";

    private String currentLang;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences prefs = getSharedPreferences("devicereset_ui", MODE_PRIVATE);
        currentLang = prefs.getString(PREFS_LANG, LANG_ZH);

        // 玻璃拟态按钮
        float radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        float borderPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());
        Button btnConfig = findViewById(R.id.btn_open_config);
        Button btnLang = findViewById(R.id.btn_switch_lang);
        Button btnCheckUpdate = findViewById(R.id.btn_check_update);
        btnConfig.setBackground(new GlassButtonDrawable(radiusPx, borderPx, false));
        btnLang.setBackground(new GlassButtonDrawable(radiusPx, borderPx, false));
        btnCheckUpdate.setBackground(new GlassButtonDrawable(radiusPx, borderPx, false));

        updateUI();

        btnConfig.setOnClickListener(v ->
                startActivity(new Intent(this, ConfigActivity.class)));

        // 手动点击检查更新
        btnCheckUpdate.setOnClickListener(v -> checkUpdate(true));

        // 自动检测更新（启动时后台检查，有新版本才提示）
        checkUpdate(false);

        // 三语循环切换：中 -> 英 -> 俄 -> 中
        btnLang.setOnClickListener(v -> {
            if (LANG_ZH.equals(currentLang)) {
                currentLang = LANG_EN;
            } else if (LANG_EN.equals(currentLang)) {
                currentLang = LANG_RU;
            } else {
                currentLang = LANG_ZH;
            }
            prefs.edit().putString(PREFS_LANG, currentLang).apply();
            updateUI();
        });
    }

    private void updateUI() {
        TextView tvSubtitle = findViewById(R.id.tv_subtitle);
        TextView tvUsageTitle = findViewById(R.id.tv_usage_title);
        TextView tvUsageContent = findViewById(R.id.tv_usage_content);
        TextView tvPrincipleTitle = findViewById(R.id.tv_principle_title);
        TextView tvPrincipleContent = findViewById(R.id.tv_principle_content);
        TextView tvSpoofTitle = findViewById(R.id.tv_spoof_title);
        TextView tvSpoofContent = findViewById(R.id.tv_spoof_content);
        TextView tvWarningTitle = findViewById(R.id.tv_warning_title);
        TextView tvWarningContent = findViewById(R.id.tv_warning_content);
        Button btnConfig = findViewById(R.id.btn_open_config);
        Button btnLang = findViewById(R.id.btn_switch_lang);
        Button btnCheckUpdate = findViewById(R.id.btn_check_update);

        if (LANG_ZH.equals(currentLang)) {
            tvSubtitle.setText("清除应用数据后自动生成全新设备识别码");
            tvUsageTitle.setText("📖 使用方法");
            tvUsageContent.setText(
                    "1. LSPosed管理器 → 模块 → 启用本模块 → 作用域勾选目标应用\n" +
                    "2. 重启手机（必须重启）\n" +
                    "3. 点击上方「运行日志」按钮进入配置\n" +
                    "4. 系统设置 → 应用 → 目标应用 → 存储 → 清除数据\n" +
                    "5. 重新打开应用，即获得全新设备身份");
            tvPrincipleTitle.setText("🔧 工作原理");
            tvPrincipleContent.setText(
                    "模块在目标应用私有目录放置隐藏哨兵文件。清除应用数据会删除整个私有目录，哨兵文件也被删除。下次应用启动时检测到哨兵不存在，即生成全新设备身份并写入新哨兵。");
            tvSpoofTitle.setText("🎭 伪装的识别码");
            tvSpoofContent.setText(
                    "• Android ID (SSAID)\n" +
                    "• 广告ID (AAID) / AppSet ID\n" +
                    "• IMEI / MEID / IMSI / ICCID\n" +
                    "• Serial序列号 / MAC地址\n" +
                    "• GSF ID\n" +
                    "• 设备型号：品牌、型号、厂商、Build指纹\n" +
                    "• 运营商信息：代码、名称、国家");
            tvWarningTitle.setText("⚠️ 注意事项");
            tvWarningContent.setText(
                    "• 本模块仅用于个人隐私保护和技术测试\n" +
                    "• 部分应用会检测Xposed/Root痕迹，存在账号封禁风险\n" +
                    "• 加壳应用请在LSPosed作用域设置中勾选「排除资源钩子」\n" +
                    "• native层直接读取系统属性的应用，Java层Hook无法拦截\n" +
                    "• 建议先在不重要的应用上测试\n" +
                    "• 排查问题：LSPosed → 日志 → 搜索「DeviceReset」\n" +
                    "• 配置界面右上角菜单可手动重置身份");
            btnConfig.setText("📋 运行日志");
            btnLang.setText("🌐 中/EN/RU");
            btnCheckUpdate.setText("🔄 检查更新");
        } else if (LANG_EN.equals(currentLang)) {
            tvSubtitle.setText("Auto-generate new device identity after clearing app data");
            tvUsageTitle.setText("📖 Usage");
            tvUsageContent.setText(
                    "1. LSPosed Manager → Modules → Enable this module → Check target apps in Scope\n" +
                    "2. Reboot phone (required)\n" +
                    "3. Tap \"Run Log\" button above to enter config\n" +
                    "4. System Settings → Apps → Target app → Storage → Clear data\n" +
                    "5. Reopen the app to get a brand new device identity");
            tvPrincipleTitle.setText("🔧 How It Works");
            tvPrincipleContent.setText(
                    "The module places a hidden sentinel file in the target app's private directory. Clearing app data deletes the entire private directory, including the sentinel file. On next launch, the module detects the missing sentinel and generates a new device identity, writing a new sentinel file.");
            tvSpoofTitle.setText("🎭 Spoofed Identifiers");
            tvSpoofContent.setText(
                    "• Android ID (SSAID)\n" +
                    "• Advertising ID (AAID) / AppSet ID\n" +
                    "• IMEI / MEID / IMSI / ICCID\n" +
                    "• Serial number / MAC address\n" +
                    "• GSF ID\n" +
                    "• Device info: Brand, Model, Manufacturer, Build fingerprint\n" +
                    "• Carrier info: Code, Name, Country");
            tvWarningTitle.setText("⚠️ Warnings");
            tvWarningContent.setText(
                    "• For personal privacy protection and technical testing only\n" +
                    "• Some apps detect Xposed/Root traces, account ban risk exists\n" +
                    "• For packed/protected apps, enable \"Exclude resource hooks\" in LSPosed scope settings\n" +
                    "• Apps reading system properties directly at native layer cannot be intercepted by Java hooks\n" +
                    "• Test on non-critical apps first\n" +
                    "• Troubleshooting: LSPosed → Logs → Search \"DeviceReset\"\n" +
                    "• Manually reset identity from the config app's menu");
            btnConfig.setText("📋 Run Log");
            btnLang.setText("🌐 中/EN/RU");
            btnCheckUpdate.setText("🔄 Check Update");
        } else {
            // Russian
            tvSubtitle.setText("Автоматическая генерация новой идентификации устройства после очистки данных");
            tvUsageTitle.setText("📖 Использование");
            tvUsageContent.setText(
                    "1. LSPosed Manager → Модули → Включить модуль → Отметить целевые приложения в Области\n" +
                    "2. Перезагрузите телефон (обязательно)\n" +
                    "3. Нажмите кнопку «Журнал запуска» выше для входа в настройки\n" +
                    "4. Настройки системы → Приложения → Целевое приложение → Память → Очистить данные\n" +
                    "5. Переоткройте приложение, чтобы получить новую идентификацию");
            tvPrincipleTitle.setText("🔧 Как это работает");
            tvPrincipleContent.setText(
                    "Модуль помещает скрытый файл-sentinel в приватный каталог целевого приложения. Очистка данных удаляет весь приватный каталог, включая файл-sentinel. При следующем запуске модуль обнаруживает отсутствие sentinel и генерирует новую идентификацию устройства, записывая новый файл-sentinel.");
            tvSpoofTitle.setText("🎭 Подменяемые идентификаторы");
            tvSpoofContent.setText(
                    "• Android ID (SSAID)\n" +
                    "• Рекламный ID (AAID) / AppSet ID\n" +
                    "• IMEI / MEID / IMSI / ICCID\n" +
                    "• Серийный номер / MAC-адрес\n" +
                    "• GSF ID\n" +
                    "• Информация об устройстве: бренд, модель, производитель, отпечаток Build\n" +
                    "• Информация об операторе: код, название, страна");
            tvWarningTitle.setText("⚠️ Предупреждения");
            tvWarningContent.setText(
                    "• Только для защиты личной конфиденциальности и технического тестирования\n" +
                    "• Некоторые приложения обнаруживают следы Xposed/Root, существует риск блокировки аккаунта\n" +
                    "• Для упакованных приложений включите «Исключить хуки ресурсов» в настройках области LSPosed\n" +
                    "• Приложения, читающие системные свойства на нативном уровне, не могут быть перехвачены Java-хуками\n" +
                    "• Сначала тестируйте на некритичных приложениях\n" +
                    "• Устранение неполадок: LSPosed → Журналы → Поиск «DeviceReset»\n" +
                    "• Ручной сброс идентичности из меню в правом верхнем углу настроек");
            btnConfig.setText("📋 Журнал");
            btnLang.setText("🌐 中/EN/RU");
            btnCheckUpdate.setText("🔄 Проверить обновления");
        }
    }

    /**
     * 检查更新：manual=false 为启动时自动检测（有新版本才弹窗），
     * manual=true 为手动点击（无更新/失败时给出提示）。
     */
    private void checkUpdate(boolean manual) {
        String versionName;
        try {
            versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            versionName = "0";
        }
        UpdateChecker.check(UPDATE_REPO, versionName,
                (latest, tag, hasUpdate, error) -> {
                    if (hasUpdate) {
                        showUpdateAvailableDialog(latest, tag);
                    } else if (manual) {
                        if (error != null) {
                            Toast.makeText(this,
                                    getCheckErrorText() + error,
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this,
                                    getUpToDateText(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private String getUpdateTitle(String latest) {
        if (LANG_ZH.equals(currentLang)) return "🔄 发现新版本 v" + latest;
        if (LANG_EN.equals(currentLang)) return "🔄 New version v" + latest + " available";
        return "🔄 Доступна новая версия v" + latest;
    }

    private String getUpdateMessage(String latest) {
        if (LANG_ZH.equals(currentLang)) return "检测到新版本 v" + latest + "，是否下载？";
        if (LANG_EN.equals(currentLang)) return "New version v" + latest + " detected. Download?";
        return "Обнаружена новая версия v" + latest + ". Скачать?";
    }

    /**
     * "发现新版本"弹窗：圆角背景 + 玻璃按钮，与下载进度弹窗风格一致。
     */
    private void showUpdateAvailableDialog(String latest, String tag) {
        final float density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * density);
        root.setPadding(pad, Math.round(16 * density), pad, Math.round(20 * density));
        root.setBackground(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        TextView title = new TextView(this);
        title.setText(getUpdateTitle(latest));
        title.setTextSize(18);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView msg = new TextView(this);
        msg.setText(getUpdateMessage(latest));
        msg.setTextSize(14);
        msg.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.topMargin = Math.round(10 * density);
        root.addView(msg, msgLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(18 * density);
        btnRow.setLayoutParams(rowLp);

        float radiusPx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        float borderPx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());

        Button cancelBtn = new Button(this);
        cancelBtn.setText(getUpdateNegative());
        cancelBtn.setTextSize(14);
        cancelBtn.setBackground(new GlassButtonDrawable(radiusPx, borderPx, false));

        Button downloadBtn = new Button(this);
        downloadBtn.setText(getUpdatePositive());
        downloadBtn.setTextSize(14);
        downloadBtn.setBackground(new GlassButtonDrawable(radiusPx, borderPx, false));

        int btnMargin = Math.round(4 * density);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.leftMargin = btnMargin;
        btnLp.rightMargin = btnMargin;
        btnRow.addView(cancelBtn, btnLp);
        btnRow.addView(downloadBtn, btnLp);
        root.addView(btnRow, rowLp);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .setCancelable(true)
                .create();
        dialog.show();

        android.graphics.drawable.GradientDrawable dialogBg = new android.graphics.drawable.GradientDrawable();
        dialogBg.setColor(0xFF2B2B2B);
        dialogBg.setCornerRadius(radiusPx);
        dialog.getWindow().setBackgroundDrawable(dialogBg);

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        downloadBtn.setOnClickListener(v -> {
            dialog.dismiss();
            startInAppDownload(latest, tag);
        });
    }

    /**
     * 内置下载：构建直连 + 镜像候选地址，交给 AppDownloader 下载并弹进度条。
     */
    private void startInAppDownload(String version, String tag) {
        String asset = "DeviceResetSpoofer-v" + version + ".apk";
        String[] urls = buildDownloadUrls(tag, asset);
        AppDownloader.start(this, urls, REPO_HOME_URL,
                "DeviceResetSpoofer-v" + version + ".apk", version, currentLang);
    }

    private String[] buildDownloadUrls(String tag, String asset) {
        String direct = "https://github.com/" + UPDATE_REPO
                + "/releases/download/" + tag + "/" + asset;
        String[] mirrors = {
                "https://ghfast.top/",
                "https://gh-proxy.com/",
                "https://ghproxy.net/",
                "https://gh.llkk.cc/",
                "https://mirror.ghproxy.com/",
                "https://github.moeyy.xyz/"
        };
        // 代理优先（国内直连 GitHub 会超时），直连兜底
        String[] urls = new String[1 + mirrors.length];
        for (int i = 0; i < mirrors.length; i++) {
            urls[i] = mirrors[i] + direct;
        }
        urls[mirrors.length] = direct;
        return urls;
    }

    private String getUpdatePositive() {
        if (LANG_ZH.equals(currentLang)) return "下载";
        if (LANG_EN.equals(currentLang)) return "Download";
        return "Скачать";
    }

    private String getUpdateNegative() {
        if (LANG_ZH.equals(currentLang)) return "取消";
        if (LANG_EN.equals(currentLang)) return "Cancel";
        return "Отмена";
    }

    private String getUpToDateText() {
        if (LANG_ZH.equals(currentLang)) return "已是最新版本";
        if (LANG_EN.equals(currentLang)) return "You are up to date";
        return "У вас последняя версия";
    }

    private String getCheckErrorText() {
        if (LANG_ZH.equals(currentLang)) return "检查更新失败：";
        if (LANG_EN.equals(currentLang)) return "Update check failed: ";
        return "Не удалось проверить обновления: ";
    }
}
