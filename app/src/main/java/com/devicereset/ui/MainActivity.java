package com.devicereset.ui;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.devicereset.Config;
import com.devicereset.R;
import com.devicereset.xposed.SentinelDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模块配置主界面。
 * 功能：
 * 1. 选择目标应用（勾选后，清除该应用数据会自动换设备身份）
 * 2. 各Hook项开关
 * 3. 手动重置某个应用的身份
 * 4. 搜索/过滤应用
 */
public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private AppListAdapter adapter;
    private EditText searchEditText;
    private TextView selectedCountText;
    private Switch showSystemSwitch;
    private Switch autoResetSwitch;
    private Switch hookAndroidIdSwitch;
    private Switch hookAdIdSwitch;
    private Switch hookImeiSwitch;
    private Switch hookBuildSwitch;
    private Switch hookMacSwitch;
    private Switch hookGsfSwitch;
    private Switch hookCarrierSwitch;

    private List<AppInfo> allApps = new ArrayList<>();
    private Set<String> selectedPackages = new HashSet<>();
    private boolean showSystemApps = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        loadConfig();
        loadApps();
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
                    adapter.filter(s.toString());
                }
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        showSystemSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showSystemApps = isChecked;
            updateAppList();
        });

        // 使用说明按钮
        findViewById(R.id.btn_help).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, HelpActivity.class)));

        // 各开关监听
        autoResetSwitch.setOnCheckedChangeListener((v, checked) -> Config.setAutoReset(this, checked));
        hookAndroidIdSwitch.setOnCheckedChangeListener((v, checked) -> Config.setHookAndroidId(this, checked));
        hookAdIdSwitch.setOnCheckedChangeListener((v, checked) -> Config.setHookAdId(this, checked));
        hookImeiSwitch.setOnCheckedChangeListener((v, checked) -> Config.setHookImei(this, checked));
        hookBuildSwitch.setOnCheckedChangeListener((v, checked) -> Config.setHookBuild(this, checked));
        hookMacSwitch.setOnCheckedChangeListener((v, checked) -> Config.setHookMac(this, checked));
        hookGsfSwitch.setOnCheckedChangeListener((v, checked) -> Config.setHookGsf(this, checked));
        hookCarrierSwitch.setOnCheckedChangeListener((v, checked) -> Config.setHookCarrier(this, checked));
    }

    private void loadConfig() {
        selectedPackages = new HashSet<>(Config.getTargetPackages(this));
        autoResetSwitch.setChecked(Config.isAutoReset(this));
        hookAndroidIdSwitch.setChecked(Config.isHookAndroidId(this));
        hookAdIdSwitch.setChecked(Config.isHookAdId(this));
        hookImeiSwitch.setChecked(Config.isHookImei(this));
        hookBuildSwitch.setChecked(Config.isHookBuild(this));
        hookMacSwitch.setChecked(Config.isHookMac(this));
        hookGsfSwitch.setChecked(Config.isHookGsf(this));
        hookCarrierSwitch.setChecked(Config.isHookCarrier(this));
        updateSelectedCount();
    }

    private void loadApps() {
        AsyncTask.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            List<AppInfo> appInfoList = new ArrayList<>();

            for (ApplicationInfo app : apps) {
                // 跳过自己
                if (app.packageName.equals(getPackageName())) continue;

                boolean isSystem = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                String appName = app.loadLabel(pm).toString();
                AppInfo info = new AppInfo(
                        app.packageName,
                        appName,
                        app.loadIcon(pm),
                        isSystem,
                        selectedPackages.contains(app.packageName)
                );
                appInfoList.add(info);
            }

            // 按应用名排序
            Collections.sort(appInfoList, Comparator.comparing(a -> a.appName.toLowerCase()));

            allApps = appInfoList;

            runOnUiThread(() -> updateAppList());
        });
    }

    private void updateAppList() {
        List<AppInfo> displayList = new ArrayList<>();
        for (AppInfo app : allApps) {
            if (!showSystemApps && app.isSystemApp) continue;
            displayList.add(app);
        }

        if (adapter == null) {
            adapter = new AppListAdapter(displayList, (appInfo, checked) -> {
                if (checked) {
                    selectedPackages.add(appInfo.packageName);
                } else {
                    selectedPackages.remove(appInfo.packageName);
                }
                Config.setTargetPackages(this, selectedPackages);
                updateSelectedCount();
            });
            recyclerView.setAdapter(adapter);
        } else {
            adapter.updateList(displayList);
        }
    }

    private void updateSelectedCount() {
        selectedCountText.setText(String.format("已选择 %d 个应用", selectedPackages.size()));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "手动重置身份");
        menu.add(0, 3, 1, "使用说明");
        menu.add(0, 2, 2, "关于");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            showResetDialog();
            return true;
        } else if (item.getItemId() == 3) {
            startActivity(new android.content.Intent(this, HelpActivity.class));
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
                .setMessage("选择要重置身份的应用，重置后该应用下次启动将获得全新设备身份。" +
                        "\n\n注意：这等同于清除该应用数据后的效果，但不会删除应用数据。")
                .setItems(packages, (dialog, which) -> {
                    String pkg = packages[which];
                    boolean success = SentinelDetector.resetIdentity(pkg, this);
                    if (success) {
                        Toast.makeText(this, "已重置 " + pkg + " 的身份，下次启动生效",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "重置失败，请确保已授予ROOT权限或应用已安装",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("DeviceResetSpoofer")
                .setMessage("版本：1.0\n\n" +
                        "功能：对选中的应用，在清除应用数据后自动生成全新的设备识别码。\n\n" +
                        "原理：在目标应用私有目录放置哨兵文件，清除数据后哨兵文件被删除，" +
                        "下次启动检测到哨兵不存在即生成全新身份。\n\n" +
                        "使用方法：\n" +
                        "1. 在LSPosed中启用本模块并勾选目标应用\n" +
                        "2. 在本界面勾选需要保护的应用\n" +
                        "3. 清除该应用数据后，下次打开自动获得新设备身份")
                .setPositiveButton("确定", null)
                .show();
    }
}
