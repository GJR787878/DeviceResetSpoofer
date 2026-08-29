package com.devicereset.xposed;

import android.content.pm.ApplicationInfo;

import com.devicereset.hooks.AdvertisingIdHook;
import com.devicereset.hooks.AndroidIdHook;
import com.devicereset.hooks.BuildInfoHook;
import com.devicereset.hooks.GsfIdHook;
import com.devicereset.hooks.TelephonyHook;
import com.devicereset.hooks.WifiMacHook;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed模块主入口。
 *
 * 采用双重Hook确保生效：
 * 1. handleLoadPackage中读取配置
 * 2. hook ActivityThread.handleBindApplication（最早点，Application创建之前）
 *    在这个点检测哨兵文件并安装所有设备ID Hook，确保APP任何代码都拿不到真实ID
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String MODULE_PACKAGE = "com.devicereset";
    private static final String PREFS_NAME = "devicereset_config";
    private static XSharedPreferences xPrefs;
    private static boolean prefsAvailable = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (MODULE_PACKAGE.equals(lpparam.packageName)) return;

        // 读取模块配置
        if (xPrefs == null) {
            try {
                xPrefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
                xPrefs.makeWorldReadable();
                prefsAvailable = true;
            } catch (Throwable t) {
                XposedBridge.log("[DeviceReset] XSharedPreferences init failed: " + t.getMessage());
                prefsAvailable = false;
            }
        }
        if (prefsAvailable) {
            try { xPrefs.reload(); } catch (Throwable ignored) {}
        }

        // 检查是否是目标应用（fallback：配置读取失败时默认生效）
        boolean isTarget = true;
        if (prefsAvailable) {
            try {
                Set<String> targets = xPrefs.getStringSet("target_packages", new HashSet<>());
                if (targets != null && !targets.isEmpty()) {
                    isTarget = targets.contains(lpparam.packageName);
                }
            } catch (Throwable t) {
                isTarget = true;
            }
        }
        if (!isTarget) return;

        // 读取各Hook开关
        final boolean hookAndroidId = getPrefBoolean("hook_android_id", true);
        final boolean hookAdId = getPrefBoolean("hook_ad_id", true);
        final boolean hookImei = getPrefBoolean("hook_imei", true);
        final boolean hookBuild = getPrefBoolean("hook_build_info", true);
        final boolean hookMac = getPrefBoolean("hook_mac", true);
        final boolean hookGsf = getPrefBoolean("hook_gsf_id", true);
        final boolean hookCarrier = getPrefBoolean("hook_carrier", true);
        final String targetPackage = lpparam.packageName;

        XposedBridge.log("[DeviceReset] handleLoadPackage for: " + targetPackage);

        // Hook ActivityThread.handleBindApplication —— 这是Application创建之前的最早点
        // 用classLoader=null因为ActivityThread是系统类，在BootClassLoader中
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
            XposedHelpers.findAndHookMethod(activityThreadClass, "handleBindApplication",
                    activityThreadClass.getClassLoader().loadClass("android.app.ActivityThread$AppBindData"),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object bindData = param.args[0];
                                ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.getObjectField(bindData, "appInfo");
                                if (appInfo == null) return;

                                // 只处理目标包名（handleBindApplication可能被其他进程调用）
                                if (!targetPackage.equals(appInfo.packageName)) return;

                                // dataDir 类似 /data/user/0/com.blackhole.network
                                // filesDir 就是 dataDir + "/files"
                                String filesDir = appInfo.dataDir + "/files";

                                XposedBridge.log("[DeviceReset] handleBindApplication for "
                                        + appInfo.packageName + ", dataDir=" + appInfo.dataDir);

                                // 核心：检测哨兵文件，决定本次身份
                                Identity identity = SentinelDetector.checkAndGetIdentityByDir(filesDir);

                                XposedBridge.log("[DeviceReset] Identity loaded: androidId="
                                        + identity.androidId + ", model=" + identity.model);

                                // 安装所有Hook
                                if (hookAndroidId) AndroidIdHook.install(lpparam, identity);
                                if (hookAdId) AdvertisingIdHook.install(lpparam, identity);
                                if (hookImei || hookCarrier) TelephonyHook.install(lpparam, identity, hookImei, hookCarrier);
                                if (hookBuild) BuildInfoHook.install(lpparam, identity);
                                if (hookMac) WifiMacHook.install(lpparam, identity);
                                if (hookGsf) GsfIdHook.install(lpparam, identity);

                                XposedBridge.log("[DeviceReset] All hooks installed for " + appInfo.packageName);

                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] handleBindApplication error: " + t.getMessage());
                            }
                        }
                    }
            );
            XposedBridge.log("[DeviceReset] handleBindApplication hook installed for " + targetPackage);
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] Failed to hook handleBindApplication: " + t.getMessage());
        }
    }

    private boolean getPrefBoolean(String key, boolean defaultValue) {
        if (!prefsAvailable || xPrefs == null) return defaultValue;
        try {
            return xPrefs.getBoolean(key, defaultValue);
        } catch (Throwable t) {
            return defaultValue;
        }
    }
}
