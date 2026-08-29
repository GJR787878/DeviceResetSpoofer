package com.devicereset.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.devicereset.Config;
import com.devicereset.R;
import com.devicereset.xposed.SentinelDetector;

public class ConfigActivity extends AppCompatActivity {
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
            setContentView(R.layout.activity_config);
            initViews();
            loadConfig();
        } catch (Throwable t) {
            Toast.makeText(this, "初始化失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
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
        menu.add(0, 1, 0, "手动重置身份");
        menu.add(0, 2, 1, "关于");
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
        final EditText input = new EditText(this);
        input.setHint("输入应用包名，如 com.example.app");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);
        layout.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("手动重置身份")
                .setMessage("输入要重置身份的应用包名。重置后该应用下次启动将获得全新设备身份（无需清除数据）。")
                .setView(layout)
                .setPositiveButton("重置", (dialog, which) -> {
                    String pkg = input.getText().toString().trim();
                    if (pkg.isEmpty()) {
                        Toast.makeText(this, "请输入包名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        boolean success = SentinelDetector.resetIdentity(pkg, this);
                        if (success) {
                            Toast.makeText(this, "已重置 " + pkg + " 的身份", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "重置失败，请确保已授予ROOT权限", Toast.LENGTH_LONG).show();
                        }
                    } catch (Throwable t) {
                        Toast.makeText(this, "重置异常：" + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("DeviceResetSpoofer")
                .setMessage("版本：1.0.2\n\n清除应用数据后自动生成全新设备识别码的LSPosed模块。\n\n直接对LSPosed作用域中勾选的应用生效。")
                .setPositiveButton("确定", null)
                .show();
    }
}
