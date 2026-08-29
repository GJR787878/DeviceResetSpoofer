package com.devicereset.xposed;

import android.app.Application;
import android.content.Context;

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
 * 工作流程：
 * 1. LSPosed作用域过滤（只有勾选的APP才会进入此方法）
 * 2. 读取模块配置（XSharedPreferences），失败时fallback到默认全部生效
 * 3. 在 Application.attachBaseContext 之前（before）检测哨兵文件并安装Hook
 * 4. 根据哨兵检测结果，决定使用旧身份还是全新身份
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String MODULE_PACKAGE = "com.devicereset";
    private static final String PREFS_NAME = "devicereset_config";
    private static XSharedPreferences xPrefs;
    private static boolean prefsAvailable = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 不hook自己
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
            try {
                xPrefs.reload();
            } catch (Throwable ignored) {}
        }

        // 检查是否是目标应用
        // fallback策略：如果配置读取失败或目标列表为空，默认对LSPosed作用域内的APP生效
        // （因为能进入handleLoadPackage说明已经在LSPosed作用域中了）
        boolean isTarget = true;
        if (prefsAvailable) {
            try {
                Set<String> targets = xPrefs.getStringSet("target_packages", new HashSet<>());
                if (targets != null && !targets.isEmpty()) {
                    isTarget = targets.contains(lpparam.packageName);
                }
                // 如果targets为空，说明用户还没在配置界面勾选，默认生效
            } catch (Throwable t) {
                XposedBridge.log("[DeviceReset] read target_packages failed: " + t.getMessage());
                isTarget = true; // fallback
            }
        }

        if (!isTarget) return;

        // 读取各Hook开关（默认全部开启）
        final boolean hookAndroidId = getPrefBoolean("hook_android_id", true);
        final boolean hookAdId = getPrefBoolean("hook_ad_id", true);
        final boolean hookImei = getPrefBoolean("hook_imei", true);
        final boolean hookBuild = getPrefBoolean("hook_build_info", true);
        final boolean hookMac = getPrefBoolean("hook_mac", true);
        final boolean hookGsf = getPrefBoolean("hook_gsf_id", true);
        final boolean hookCarrier = getPrefBoolean("hook_carrier", true);

        XposedBridge.log("[DeviceReset] Hook installed for: " + lpparam.packageName);

        // 在 Application.attachBaseContext 之前安装Hook
        // 用before而不是after，确保APP在attachBaseContext中读取设备ID时也能被拦截
        XposedHelpers.findAndHookMethod(
                Application.class,
                "attachBaseContext",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Context context = (Context) param.args[0];
                        if (context == null) return;

                        try {
                            // 核心：检测哨兵文件，决定本次身份
                            Identity identity = SentinelDetector.checkAndGetIdentity(context);

                            XposedBridge.log("[DeviceReset] Identity loaded for "
                                    + lpparam.packageName
                                    + ", androidId=" + identity.androidId);

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
                        } catch (Throwable t) {
                            XposedBridge.log("[DeviceReset] Hook install failed: " + t.getMessage());
                        }
                    }
                }
        );
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
