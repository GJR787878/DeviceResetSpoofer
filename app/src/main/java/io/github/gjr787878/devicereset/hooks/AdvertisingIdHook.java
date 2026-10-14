package io.github.gjr787878.devicereset.hooks;

import io.github.gjr787878.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook 广告 ID（Advertising ID / AAID）与 AppSet ID。
 *
 * <p>关键：{@code getAdvertisingIdInfo} 在 GMS 不可用 / 无服务时<b>原方法直接抛异常</b>，
 * 用 after 不执行。改为 before 短路：构造假 Info 直接返回；若该 GMS 版本构造签名不同导致
 * 构造失败，则放行原方法、由 Info.getId 的 hook 兜底。</p>
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
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object fakeInfo = XposedHelpers.newInstance(infoClass,
                                        identity.advertisingId, false);
                                param.setResult(fakeInfo);
                            } catch (Throwable t) {
                                // 构造失败（GMS 版本签名不同）：放行原方法，由 Info.getId hook 兜底
                                XposedBridge.log("[DeviceReset] build fake Info failed, fallback to getId: " + t.getMessage());
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(infoClass, "getId",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
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

        // AppSet ID：原方法无服务时也可能抛异常，用 before 短路
        try {
            Class<?> appSetIdClass = XposedHelpers.findClass(
                    "com.google.android.gms.appset.AppSetId",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(appSetIdClass, "getAppSetId",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (identity.appSetId != null) param.setResult(identity.appSetId);
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] getAppSetId error: " + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }
}
