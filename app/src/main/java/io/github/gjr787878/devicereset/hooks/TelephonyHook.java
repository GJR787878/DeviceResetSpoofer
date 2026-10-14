package io.github.gjr787878.devicereset.hooks;

import android.telephony.TelephonyManager;

import io.github.gjr787878.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook 电话相关信息：IMEI、MEID、IMSI、ICCID、运营商信息。
 *
 * <p>关键：Android 10+ 起，无 {@code READ_PRIVILEGED_PHONE_STATE}（系统应用/特权应用）时，
 * getDeviceId/getImei/getMeid/getSubscriberId/getSimSerialNumber 等方法<b>在原方法内直接抛
 * SecurityException</b>。若用 {@code afterHookedMethod}，原方法已抛异常、after 不会执行，
 * 假值无法返回。因此全部改用 {@code beforeHookedMethod} + setResult 在原方法执行前短路。</p>
 */
public class TelephonyHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, Identity identity,
                               boolean hookImei, boolean hookCarrier) {
        if (hookImei) {
            hookMethodReturn("getDeviceId", identity.imei);
            hookMethodReturnInt("getDeviceId", int.class, identity.imei);
            hookMethodReturn("getImei", identity.imei);
            hookMethodReturnInt("getImei", int.class, identity.imei);
            hookMethodReturn("getMeid", identity.meid);
            hookMethodReturnInt("getMeid", int.class, identity.meid);
            hookMethodReturn("getSubscriberId", identity.imsi);
            hookMethodReturnInt("getSubscriberId", int.class, identity.imsi);
            hookMethodReturn("getSimSerialNumber", identity.iccid);
            hookMethodReturnInt("getSimSerialNumber", int.class, identity.iccid);
        }

        if (hookCarrier) {
            // 运营商名/编号通常不抛权限，但同样用 before 短路，保证一次写对。
            hookMethodReturn("getNetworkOperator", identity.networkOperator);
            hookMethodReturn("getNetworkOperatorName", identity.networkOperatorName);
            hookMethodReturn("getSimOperator", identity.simOperator);
            hookMethodReturn("getSimOperatorName", identity.simOperatorName);
            hookMethodReturn("getSimCountryIso", identity.simCountryIso);
            hookMethodReturn("getNetworkCountryIso", identity.networkCountryIso);
        }
    }

    private static void hookMethodReturn(String methodName, String value) {
        if (value == null) return;
        try {
            XposedHelpers.findAndHookMethod(TelephonyManager.class, methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                param.setResult(value);
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] " + methodName + " error: " + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private static void hookMethodReturnInt(String methodName, Class<?> paramType, String value) {
        if (value == null) return;
        try {
            XposedHelpers.findAndHookMethod(TelephonyManager.class, methodName, paramType,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                param.setResult(value);
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] " + methodName + "(int) error: " + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }
}
