package com.devicereset.hooks;

import android.content.ContentResolver;
import android.provider.Settings;

import com.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook Android ID (SSAID)。
 */
public class AndroidIdHook {
    public static void install(XC_LoadPackage.LoadPackageParam lpparam, Identity identity) {
        if (identity.androidId == null) return;
        try {
            XposedHelpers.findAndHookMethod(
                    Settings.Secure.class,
                    "getString",
                    ContentResolver.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length > 1 && "android_id".equals(param.args[1])) {
                                    param.setResult(identity.androidId);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] AndroidIdHook getString error: " + t.getMessage());
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] AndroidIdHook install getString failed: " + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(
                    Settings.Secure.class,
                    "getStringForUser",
                    ContentResolver.class,
                    String.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length > 1 && "android_id".equals(param.args[1])) {
                                    param.setResult(identity.androidId);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] AndroidIdHook getStringForUser error: " + t.getMessage());
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}
    }
}
