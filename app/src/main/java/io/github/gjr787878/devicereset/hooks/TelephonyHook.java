package io.github.gjr787878.devicereset.hooks;

import android.telephony.TelephonyManager;

import io.github.gjr787878.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook 电话相关信息：IMEI、MEID、运营商信息。
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

            try {
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getSubscriberId",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    String imsi = identity.simOperator + String.format("%09d",
                                            (int) (Math.random() * 1000000000L));
                                    param.setResult(imsi);
                                } catch (Throwable t) {
                                    XposedBridge.log("[DeviceReset] getSubscriberId error: " + t.getMessage());
                                }
                            }
                        });
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "getSimSerialNumber",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    StringBuilder iccid = new StringBuilder("8986");
                                    for (int i = 0; i < 16; i++) {
                                        iccid.append((int) (Math.random() * 10));
                                    }
                                    param.setResult(iccid.toString());
                                } catch (Throwable t) {
                                    XposedBridge.log("[DeviceReset] getSimSerialNumber error: " + t.getMessage());
                                }
                            }
                        });
            } catch (Throwable ignored) {}
        }

        if (hookCarrier) {
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
                        protected void afterHookedMethod(MethodHookParam param) {
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
                        protected void afterHookedMethod(MethodHookParam param) {
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
