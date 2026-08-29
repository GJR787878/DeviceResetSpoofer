package com.devicereset.xposed;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;

import com.devicereset.hooks.AdvertisingIdHook;
import com.devicereset.hooks.AndroidIdHook;
import com.devicereset.hooks.BuildInfoHook;
import com.devicereset.hooks.GsfIdHook;
import com.devicereset.hooks.TelephonyHook;
import com.devicereset.hooks.WifiMacHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed模块主入口。
 *
 * 工作流程：
 * 1. 检查当前包名是否在用户配置的目标列表中
 * 2. 在 Application.attachBaseContext（最早可用点）检测哨兵文件
 * 3. 根据哨兵检测结果，决定使用旧身份还是全新身份
 * 4. 安装所有设备识别码Hook
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String MODULE_PACKAGE = "com.devicereset";
    private static XSharedPreferences xPrefs;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 不hook自己
        if (MODULE_PACKAGE.equals(lpparam.packageName)) return;

        // 读取模块配置（XSharedPreferences可以跨进程读取模块的SharedPreferences）
        if (xPrefs == null) {
            xPrefs = new XSharedPreferences(MODULE_PACKAGE, "devicereset_config");
            xPrefs.makeWorldReadable();
        }
        xPrefs.reload();

        // 检查是否是目标应用
        java.util.Set<String> targets = xPrefs.getStringSet("target_packages", new java.util.HashSet<>());
        if (!targets.contains(lpparam.packageName)) return;

        // 读取各Hook开关
        final boolean hookAndroidId = xPrefs.getBoolean("hook_android_id", true);
        final boolean hookAdId = xPrefs.getBoolean("hook_ad_id", true);
        final boolean hookImei = xPrefs.getBoolean("hook_imei", true);
        final boolean hookBuild = xPrefs.getBoolean("hook_build_info", true);
        final boolean hookMac = xPrefs.getBoolean("hook_mac", true);
        final boolean hookGsf = xPrefs.getBoolean("hook_gsf_id", true);
        final boolean hookCarrier = xPrefs.getBoolean("hook_carrier", true);

        // 在最早的时机：Application.attachBaseContext
        // 这个方法在Application的onCreate之前调用，是获取Context的最早点
        XposedHelpers.findAndHookMethod(
                Application.class,
                "attachBaseContext",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context context = (Context) param.args[0];

                        // 核心：检测哨兵文件，决定本次身份
                        Identity identity = SentinelDetector.checkAndGetIdentity(context);

                        // 安装所有Hook
                        if (hookAndroidId) {
                            AndroidIdHook.install(lpparam, identity);
                        }
                        if (hookAdId) {
                            AdvertisingIdHook.install(lpparam, identity);
                        }
                        if (hookImei || hookCarrier) {
                            TelephonyHook.install(lpparam, identity, hookImei, hookCarrier);
                        }
                        if (hookBuild) {
                            BuildInfoHook.install(lpparam, identity);
                        }
                        if (hookMac) {
                            WifiMacHook.install(lpparam, identity);
                        }
                        if (hookGsf) {
                            GsfIdHook.install(lpparam, identity);
                        }
                    }
                }
        );
    }
}
