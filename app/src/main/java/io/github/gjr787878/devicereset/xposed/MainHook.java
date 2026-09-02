package io.github.gjr787878.devicereset.xposed;

import android.content.pm.ApplicationInfo;

import io.github.gjr787878.devicereset.hooks.AdvertisingIdHook;
import io.github.gjr787878.devicereset.hooks.AndroidIdHook;
import io.github.gjr787878.devicereset.hooks.BuildInfoHook;
import io.github.gjr787878.devicereset.hooks.GsfIdHook;
import io.github.gjr787878.devicereset.hooks.TelephonyHook;
import io.github.gjr787878.devicereset.hooks.WifiMacHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed模块主入口。
 *
 * 直接对所有LSPosed作用域中的应用生效，无需在模块内再次选择应用。
 * 通过反射获取当前ActivityThread的mBoundApplication.appInfo.dataDir，
 * 立刻执行哨兵检测并安装所有设备ID Hook。
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

        // 直接对所有LSPosed作用域中的应用生效
        XposedBridge.log("[DeviceReset] handleLoadPackage for: " + lpparam.packageName);

        // 读取各Hook开关
        final boolean hookAndroidId = getPrefBoolean("hook_android_id", true);
        final boolean hookAdId = getPrefBoolean("hook_ad_id", true);
        final boolean hookImei = getPrefBoolean("hook_imei", true);
        final boolean hookBuild = getPrefBoolean("hook_build_info", true);
        final boolean hookMac = getPrefBoolean("hook_mac", true);
        final boolean hookGsf = getPrefBoolean("hook_gsf_id", true);
        final boolean hookCarrier = getPrefBoolean("hook_carrier", true);

        try {
            // 通过反射获取当前进程的dataDir
            String filesDir = getDataDir(lpparam.packageName);
            if (filesDir == null) {
                XposedBridge.log("[DeviceReset] ERROR: cannot get dataDir for " + lpparam.packageName);
                return;
            }
            XposedBridge.log("[DeviceReset] dataDir resolved: " + filesDir);

            // 核心：检测哨兵文件，决定本次身份
            Identity identity = SentinelDetector.checkAndGetIdentityByDir(filesDir);
            XposedBridge.log("[DeviceReset] Identity loaded: androidId=" + identity.androidId
                    + ", model=" + identity.model
                    + ", brand=" + identity.brand);

            // 安装所有Hook
            if (hookAndroidId) {
                AndroidIdHook.install(lpparam, identity);
                XposedBridge.log("[DeviceReset] AndroidId hook installed");
            }
            if (hookAdId) {
                AdvertisingIdHook.install(lpparam, identity);
                XposedBridge.log("[DeviceReset] AdvertisingId hook installed");
            }
            if (hookImei || hookCarrier) {
                TelephonyHook.install(lpparam, identity, hookImei, hookCarrier);
                XposedBridge.log("[DeviceReset] Telephony hook installed");
            }
            if (hookBuild) {
                BuildInfoHook.install(lpparam, identity);
                XposedBridge.log("[DeviceReset] BuildInfo hook installed");
            }
            if (hookMac) {
                WifiMacHook.install(lpparam, identity);
                XposedBridge.log("[DeviceReset] WifiMac hook installed");
            }
            if (hookGsf) {
                GsfIdHook.install(lpparam, identity);
                XposedBridge.log("[DeviceReset] GsfId hook installed");
            }

            XposedBridge.log("[DeviceReset] ALL hooks installed successfully for " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] FATAL error: " + t.getMessage());
            XposedBridge.log(t);
        }
    }

    /**
     * 获取当前进程的filesDir路径。
     */
    private String getDataDir(String packageName) {
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            if (activityThread != null) {
                Object boundData = XposedHelpers.getObjectField(activityThread, "mBoundApplication");
                if (boundData != null) {
                    ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.getObjectField(boundData, "appInfo");
                    if (appInfo != null && appInfo.dataDir != null) {
                        return appInfo.dataDir + "/files";
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] get dataDir via ActivityThread failed: " + t.getMessage());
        }
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object app = XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication");
            if (app != null) {
                java.io.File filesDir = (java.io.File) XposedHelpers.callMethod(app, "getFilesDir");
                if (filesDir != null) {
                    return filesDir.getAbsolutePath();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] get dataDir via Application failed: " + t.getMessage());
        }
        return "/data/data/" + packageName + "/files";
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
