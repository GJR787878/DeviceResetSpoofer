package com.devicereset.xposed;

import android.app.ActivityThread;
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
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed模块主入口。
 *
 * 关键：直接在handleLoadPackage中完成所有操作，不依赖额外Hook。
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
     * 优先通过ActivityThread.currentActivityThread().mBoundApplication.appInfo.dataDir获取，
     * fallback到包名构建路径。
     */
    private String getDataDir(String packageName) {
        try {
            // 方式1：通过ActivityThread获取
            ActivityThread activityThread = ActivityThread.currentActivityThread();
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
            // 方式2：通过当前Application获取
            android.app.Application app = ActivityThread.currentApplication();
            if (app != null) {
                return app.getFilesDir().getAbsolutePath();
            }
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] get dataDir via Application failed: " + t.getMessage());
        }

        // 方式3：fallback，用包名构建（主用户路径）
        XposedBridge.log("[DeviceReset] using fallback dataDir path for " + packageName);
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
