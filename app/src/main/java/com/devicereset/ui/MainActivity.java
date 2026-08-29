package com.devicereset.ui;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.devicereset.Config;
import com.devicereset.R;
import com.devicereset.xposed.SentinelDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private AppListAdapter adapter;
    private EditText searchEditText;
    private TextView selectedCountText;
    private SwitchCompat showSystemSwitch;
    private SwitchCompat autoResetSwitch;
    private SwitchCompat hookAndroidIdSwitch;
    private SwitchCompat hookAdIdSwitch;
    private SwitchCompat hookImeiSwitch;
    private SwitchCompat hookBuildSwitch;
    private SwitchCompat hookMacSwitch;
    private SwitchCompat hookGsfSwitch;
    private SwitchCompat hookCarrierSwitch;
    private List<AppInfo> allApps = new ArrayList<>();
    private Set<String> selectedPackages = new HashSet<>();
    private boolean showSystemApps = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            initViews();
            loadConfig();
            loadApps();
        } catch (Throwable t) {
            // 全局兜底，防止启动崩溃
            TextView errorView = new TextView(this);
            errorView.setText("初始化失败：" + t.getMessage() + "\n\n请清除模块数据后重试。");
            errorView.setPadding(32, 32, 32, 32);
            errorView.setTextSize(16);
            setContentView(errorView);
        }
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        searchEditText = findViewById(R.id.search_edit_text);
        selectedCountText = findViewById(R.id.selected_count);
        showSystemSwitch = findViewById(R.id.switch_show_system);
        autoResetSwitch = findViewById(R.id.switch_auto_reset);
        hookAndroidIdSwitch = findViewById(R.id.switch_hook_android_id);
        hookAdIdSwitch = findViewById(R.id.switch_hook_ad_id);
        hookImeiSwitch = findViewById(R.id.switch_hook_imei);
        hookBuildSwitch = findViewById(R.id.switch_hook_build);
        hookMacSwitch = findViewById(R.id.switch_hook_mac);
        hookGsfSwitch = findViewById(R.id.switch_hook_gsf);
        hookCarrierSwitch = findViewById(R.id.switch_hook_carrier);

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (adapter != null) {
                    adapter.filter(s != null ? s.toString() : "");
                }
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        showSystemSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showSystemApps = isChecked;
            updateAppList();
        });

        autoResetSwitch.setOnCheckedChangeListener((v, checked) -> {
            try { Config.setAutoReset(this, checked); } catch (Throwable ignored) {}
        });
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
        try {
            selectedPackages = new HashSet<>(Config.getTargetPackages(this));
        } catch (Throwable t) {
            selectedPackages = new HashSet<>();
        }
        try { autoResetSwitch.setChecked(Config.isAutoReset(this)); } catch (Throwable ignored) {}
        try { hookAndroidIdSwitch.setChecked(Config.isHookAndroidId(this)); } catch (Throwable ignored) {}
        try { hookAdIdSwitch.setChecked(Config.isHookAdId(this)); } catch (Throwable ignored) {}
        try { hookImeiSwitch.setChecked(Config.isHookImei(this)); } catch (Throwable ignored) {}
        try { hookBuildSwitch.setChecked(Config.isHookBuild(this)); } catch (Throwable ignored) {}
        try { hookMacSwitch.setChecked(Config.isHookMac(this)); } catch (Throwable ignored) {}
        try { hookGsfSwitch.setChecked(Config.isHookGsf(this)); } catch (Throwable ignored) {}
        try { hookCarrierSwitch.setChecked(Config.isHookCarrier(this)); } catch (Throwable ignored) {}
        updateSelectedCount();
    }

    private void loadApps() {
        // 用Thread替代AsyncTask，避免Android 14兼容性问题
        new Thread(() -> {
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                List<AppInfo> appInfoList = new ArrayList<>();
                String myPkg = getPackageName();

                for (ApplicationInfo app : apps) {
                    try {
                        if (app.packageName != null && app.packageName.equals(myPkg)) continue;
                        boolean isSystem = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        String appName = "";
                        try {
                            CharSequence label = app.loadLabel(pm);
                            appName = label != null ? label.toString() : app.packageName;
                        } catch (Throwable e) {
                            appName = app.packageName != null ? app.packageName : "";
                        }
                        AppInfo info = new AppInfo(
                                app.packageName,
                                appName,
                                app.loadIcon(pm),
                                isSystem,
                                selectedPackages.contains(app.packageName)
                        );
                        appInfoList.add(info);
                    } catch (Throwable ignored) {
                        // 跳过加载失败的应用
                    }
                }

                // 按应用名排序
                try {
                    Collections.sort(appInfoList, (a, b) -> {
                        String na = a.appName != null ? a.appName.toLowerCase(Locale.ROOT) : "";
                        String nb = b.appName != null ? b.appName.toLowerCase(Locale.ROOT) : "";
                        return na.compareTo(nb);
                    });
                } catch (Throwable ignored) {}

                allApps = appInfoList;
                runOnUiThread(this::updateAppList);
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                        "加载应用列表失败：" + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void updateAppList() {
        try {
            List<AppInfo> displayList = new ArrayList<>();
            for (AppInfo app : allApps) {
                if (!showSystemApps && app.isSystemApp) continue;
                displayList.add(app);
            }

            if (adapter == null) {
                adapter = new AppListAdapter(displayList, (appInfo, checked) -> {
                    try {
                        if (checked) {
                            selectedPackages.add(appInfo.packageName);
                        } else {
                            selectedPackages.remove(appInfo.packageName);
                        }
                        Config.setTargetPackages(this, selectedPackages);
                        updateSelectedCount();
                    } catch (Throwable ignored) {}
                });
                recyclerView.setAdapter(adapter);
            } else {
                adapter.updateList(displayList);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "更新列表失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateSelectedCount() {
        try {
            selectedCountText.setText(String.format(Locale.getDefault(), "已选择 %d 个应用", selectedPackages.size()));
        } catch (Throwable ignored) {}
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
        if (selectedPackages.isEmpty()) {
            Toast.makeText(this, "请先选择目标应用", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] packages = selectedPackages.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("手动重置身份")
                .setMessage("选择要重置身份的应用，重置后该应用下次启动将获得全新设备身份。")
                .setItems(packages, (dialog, which) -> {
                    String pkg = packages[which];
                    try {
                        boolean success = SentinelDetector.resetIdentity(pkg, this);
                        if (success) {
                            Toast.makeText(this, "已重置 " + pkg + " 的身份", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "重置失败", Toast.LENGTH_LONG).show();
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
                .setMessage("版本：1.0\n\n清除应用数据后自动生成全新设备识别码的LSPosed模块。\n\n使用说明见界面底部。")
                .setPositiveButton("确定", null)
                .show();
    }
}
