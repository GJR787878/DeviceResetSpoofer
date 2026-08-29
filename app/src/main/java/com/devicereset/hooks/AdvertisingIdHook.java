package com.devicereset.hooks;

import com.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook 广告ID (Advertising ID / AAID)。
 * 拦截 Google Play Services 的 AdvertisingIdClient.getAdvertisingIdInfo()。
 */
public class AdvertisingIdHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, Identity identity) {
        if (identity.advertisingId == null) return;

        // Hook AdvertisingIdClient.getAdvertisingIdInfo(Context)
        // 这个方法返回 AdvertisingIdClient.Info 对象
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
                                // 创建一个假的Info对象
                                Object fakeInfo = XposedHelpers.newInstance(infoClass,
                                        identity.advertisingId, false);
                                param.setResult(fakeInfo);
                            } catch (Throwable t) {
                                // 如果构造函数签名不对，尝试其他方式
                                try {
                                    Object fakeInfo = infoClass.newInstance();
                                    XposedHelpers.setObjectField(fakeInfo, "zza", identity.advertisingId);
                                    XposedHelpers.setBooleanField(fakeInfo, "zzb", false);
                                    param.setResult(fakeInfo);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
            );

            // 同时Hook Info.getId() 以防万一
            XposedHelpers.findAndHookMethod(
                    infoClass,
                    "getId",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(identity.advertisingId);
                        }
                    }
            );
        } catch (Throwable ignored) {
            // 目标APP没有集成Google Play Services广告库，跳过
        }

        // Hook AppSet ID (Android 12+ 谷歌新推出的应用集ID)
        try {
            Class<?> appSetIdClass = XposedHelpers.findClass(
                    "com.google.android.gms.appset.AppSetId",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    appSetIdClass,
                    "getAppSetId",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(identity.appSetId);
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }
}
