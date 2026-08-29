package com.devicereset.hooks;

import com.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook 广告ID (Advertising ID / AAID)。
 */
public class AdvertisingIdHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, Identity identity) {
        if (identity.advertisingId == null) return;

        try {
            Class<?> infoClass = XposedHelpers.findClass(
                    "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info",
                    lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                    "com.google.android.gms.ads.identifier.AdvertisingIdClient",
                    lpparam.classLoader,
                    "getAdvertisingIdInfo",
                    android.content.Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object fakeInfo = XposedHelpers.newInstance(infoClass,
                                        identity.advertisingId, false);
                                param.setResult(fakeInfo);
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] getAdvertisingIdInfo error: " + t.getMessage());
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(infoClass, "getId",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                param.setResult(identity.advertisingId);
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] AdvertisingIdInfo getId error: " + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] AdvertisingIdHook install failed (app may not have GMS): " + t.getMessage());
        }

        // AppSet ID
        try {
            Class<?> appSetIdClass = XposedHelpers.findClass(
                    "com.google.android.gms.appset.AppSetId",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(appSetIdClass, "getAppSetId",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                param.setResult(identity.appSetId);
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] getAppSetId error: " + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }
}
