package com.devicereset.hooks;

import android.net.wifi.WifiInfo;

import com.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook WiFi MAC地址。
 */
public class WifiMacHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, Identity identity) {
        if (identity.macAddress == null) return;

        try {
            XposedHelpers.findAndHookMethod(WifiInfo.class, "getMacAddress",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                param.setResult(identity.macAddress);
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] getMacAddress error: " + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] WifiMacHook getMacAddress install failed: " + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(WifiInfo.class, "getBSSID",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                param.setResult(identity.macAddress);
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] getBSSID error: " + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }
}
