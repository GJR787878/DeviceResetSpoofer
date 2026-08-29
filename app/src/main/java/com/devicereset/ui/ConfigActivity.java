package com.devicereset.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.devicereset.Config;
import com.devicereset.R;
import com.devicereset.xposed.SentinelDetector;

public class ConfigActivity extends AppCompatActivity {
    private static final String PREFS_LANG = "app_language";
    private static final String LANG_ZH = "zh";
    private static final String LANG_EN = "en";

    private String currentLang;
    private SwitchCompat hookAndroidIdSwitch;
    private SwitchCompat hookAdIdSwitch;
    private SwitchCompat hookImeiSwitch;
    private SwitchCompat hookBuildSwitch;
    private SwitchCompat hookMacSwitch;
    private SwitchCompat hookGsfSwitch;
    private SwitchCompat hookCarrierSwitch;

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

        if (isZh) {
            setTitle("应用配置");
        } else {
            setTitle("Config");
        }

        TextView tvScopeTitle = findViewById(R.id.tv_scope_title);
        TextView tvScopeContent = findViewById(R.id.tv_scope_content);
        TextView tvSpoofTitle = findViewById(R.id.tv_spoof_title);
        TextView tvAndroidId = findViewById(R.id.tv_android_id);
        TextView tvAdId = findViewById(R.id.tv_ad_id);
        TextView tvImei = findViewById(R.id.tv_imei);
        TextView tvBuild = findViewById(R.id.tv_build);
        TextView tvMac = findViewById(R.id.tv_mac);
        TextView tvGsf = findViewById(R.id.tv_gsf);
        TextView tvCarrier = findViewById(R.id.tv_carrier);
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
            tvFooter.setText("右上角菜单可手动重置身份。\n排查问题：LSPosed → 日志 → 搜索「DeviceReset」");
        } else {
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
            tvFooter.setText("Manually reset identity from top-right menu.\nTroubleshooting: LSPosed → Logs → Search \"DeviceReset\"");
        }
    }

    private void initViews() {
        hookAndroidIdSwitch = findViewById(R.id.switch_hook_android_id);
        hookAdIdSwitch = findViewById(R.id.switch_hook_ad_id);
        hookImeiSwitch = findViewById(R.id.switch_hook_imei);
        hookBuildSwitch = findViewById(R.id.switch_hook_build);
        hookMacSwitch = findViewById(R.id.switch_hook_mac);
        hookGsfSwitch = findViewById(R.id.switch_hook_gsf);
        hookCarrierSwitch = findViewById(R.id.switch_hook_carrier);

        hookAndroidIdSwitch.setOnCheckedChangeListener((v, checked) -> {
            try { Config.setHookAndroidId(this, checked); } catch (Throwable ignored) {}
        });
        hookAdIdSwitch.setOnCheckedChangeListener((v, checked) -> {
            try { Config.setHookAdId(this, checked); } catch (Throwable ignored) {}
        });
        hookImeiSwitch.setOnCheckedChangeListener((v, checked) -> {
            try { Config.setHookImei(this, checked); } catch (Throwable ignored) {}
        });
        hookBuildSwitch.setOnCheckedChangeListener((v, checked) -> {
            try { Config.setHookBuild(this, checked); } catch (Throwable ignored) {}
        });
        hookMacSwitch.setOnCheckedChangeListener((v, checked) -> {
            try { Config.setHookMac(this, checked); } catch (Throwable ignored) {}
        });
        hookGsfSwitch.setOnCheckedChangeListener((v, checked) -> {
            try { Config.setHookGsf(this, checked); } catch (Throwable ignored) {}
        });
        hookCarrierSwitch.setOnCheckedChangeListener((v, checked) -> {
            try { Config.setHookCarrier(this, checked); } catch (Throwable ignored) {}
        });
    }

    private void loadConfig() {
        try { hookAndroidIdSwitch.setChecked(Config.isHookAndroidId(this)); } catch (Throwable ignored) {}
        try { hookAdIdSwitch.setChecked(Config.isHookAdId(this)); } catch (Throwable ignored) {}
        try { hookImeiSwitch.setChecked(Config.isHookImei(this)); } catch (Throwable ignored) {}
        try { hookBuildSwitch.setChecked(Config.isHookBuild(this)); } catch (Throwable ignored) {}
        try { hookMacSwitch.setChecked(Config.isHookMac(this)); } catch (Throwable ignored) {}
        try { hookGsfSwitch.setChecked(Config.isHookGsf(this)); } catch (Throwable ignored) {}
        try { hookCarrierSwitch.setChecked(Config.isHookCarrier(this)); } catch (Throwable ignored) {}
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean isZh = LANG_ZH.equals(currentLang);
        if (isZh) {
            menu.add(0, 1, 0, "手动重置身份");
            menu.add(0, 2, 1, "关于");
        } else {
            menu.add(0, 1, 0, "Reset Identity");
            menu.add(0, 2, 1, "About");
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

    private void showResetDialog() {
        boolean isZh = LANG_ZH.equals(currentLang);
        final EditText input = new EditText(this);
        input.setHint(isZh ? "输入应用包名，如 com.example.app" : "Enter package name, e.g. com.example.app");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);
        layout.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(isZh ? "手动重置身份" : "Reset Identity")
                .setMessage(isZh ? "输入要重置身份的应用包名。重置后该应用下次启动将获得全新设备身份（无需清除数据）。"
                        : "Enter the package name of the app to reset. Next launch will get a brand new device identity (no need to clear data).")
                .setView(layout)
                .setPositiveButton(isZh ? "重置" : "Reset", (dialog, which) -> {
                    String pkg = input.getText().toString().trim();
                    if (pkg.isEmpty()) {
                        Toast.makeText(this, isZh ? "请输入包名" : "Please enter package name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        boolean success = SentinelDetector.resetIdentity(pkg, this);
                        if (success) {
                            Toast.makeText(this, (isZh ? "已重置 " : "Reset ") + pkg + (isZh ? " 的身份" : " identity"), Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, isZh ? "重置失败，请确保已授予ROOT权限" : "Reset failed, please ensure ROOT access", Toast.LENGTH_LONG).show();
                        }
                    } catch (Throwable t) {
                        Toast.makeText(this, (isZh ? "重置异常：" : "Error: ") + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(isZh ? "取消" : "Cancel", null)
                .show();
    }

    private void showAboutDialog() {
        boolean isZh = LANG_ZH.equals(currentLang);
        new AlertDialog.Builder(this)
                .setTitle("DeviceResetSpoofer")
                .setMessage(isZh ? "版本：1.0.2\n\n清除应用数据后自动生成全新设备识别码的LSPosed模块。\n\n直接对LSPosed作用域中勾选的应用生效。"
                        : "Version: 1.0.2\n\nLSPosed module that auto-generates new device identity after clearing app data.\n\nApplies to all apps checked in LSPosed scope.")
                .setPositiveButton(isZh ? "确定" : "OK", null)
                .show();
    }
}
