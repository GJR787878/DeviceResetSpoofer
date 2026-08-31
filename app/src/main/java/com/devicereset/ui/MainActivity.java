package com.devicereset.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.devicereset.R;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_LANG = "app_language";
    private static final String LANG_ZH = "zh";
    private static final String LANG_EN = "en";
    private static final String LANG_RU = "ru";

    private String currentLang;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences prefs = getSharedPreferences("devicereset_ui", MODE_PRIVATE);
        currentLang = prefs.getString(PREFS_LANG, LANG_ZH);

        updateUI();

        Button btnConfig = findViewById(R.id.btn_open_config);
        btnConfig.setOnClickListener(v ->
                startActivity(new Intent(this, ConfigActivity.class)));

        // 三语循环切换：中 -> 英 -> 俄 -> 中
        Button btnLang = findViewById(R.id.btn_switch_lang);
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
        }
    }
}
