package com.devicereset.hooks;

import android.os.Build;
import android.telephony.TelephonyManager;

import com.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook 电话相关信息：IMEI、MEID、运营商信息。
 */
public class TelephonyHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, Identity identity,
                               boolean hookImei, boolean hookCarrier) {
        // IMEI / MEID
        if (hookImei) {
            // getDeviceId() (Android 9及以下常用)
            try {
                XposedHelpers.findAndHookMethod(
                        TelephonyManager.class,
                        "getDeviceId",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(identity.imei);
                            }
                        }
                );
            } catch (Throwable ignored) {}

            // getDeviceId(int slotId)
            try {
                XposedHelpers.findAndHookMethod(
                        TelephonyManager.class,
                        "getDeviceId",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(identity.imei);
                            }
                        }
                );
            } catch (Throwable ignored) {}

            // getImei() (Android 10+)
            try {
                XposedHelpers.findAndHookMethod(
                        TelephonyManager.class,
                        "getImei",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(identity.imei);
                            }
                        }
                );
            } catch (Throwable ignored) {}

            // getImei(int slotId)
            try {
                XposedHelpers.findAndHookMethod(
                        TelephonyManager.class,
                        "getImei",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(identity.imei);
                            }
                        }
                );
            } catch (Throwable ignored) {}

            // getMeid()
            try {
                XposedHelpers.findAndHookMethod(
                        TelephonyManager.class,
                        "getMeid",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(identity.meid);
                            }
                        }
                );
            } catch (Throwable ignored) {}

            // getMeid(int slotId)
            try {
                XposedHelpers.findAndHookMethod(
                        TelephonyManager.class,
                        "getMeid",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(identity.meid);
                            }
                        }
                );
            } catch (Throwable ignored) {}

            // getSubscriberId() (IMSI)
            try {
                XposedHelpers.findAndHookMethod(
                        TelephonyManager.class,
                        "getSubscriberId",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                // IMI = MCC+MNC+MSIN，用运营商码+随机9位
                                String imsi = identity.simOperator + String.format("%09d",
                                        (int) (Math.random() * 1000000000L));
                                param.setResult(imsi);
                            }
                        }
                );
            } catch (Throwable ignored) {}

            // getSimSerialNumber() (ICCID)
            try {
                XposedHelpers.findAndHookMethod(
                        TelephonyManager.class,
                        "getSimSerialNumber",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                // ICCID: 8986 + 随机16位
                                StringBuilder iccid = new StringBuilder("8986");
                                for (int i = 0; i < 16; i++) {
                                    iccid.append((int) (Math.random() * 10));
                                }
                                param.setResult(iccid.toString());
                            }
                        }
                );
            } catch (Throwable ignored) {}
        }

        // 运营商信息
        if (hookCarrier) {
            hookCarrierMethod("getNetworkOperator", identity.networkOperator);
            hookCarrierMethod("getNetworkOperatorName", identity.networkOperatorName);
            hookCarrierMethod("getSimOperator", identity.simOperator);
            hookCarrierMethod("getSimOperatorName", identity.simOperatorName);
            hookCarrierMethod("getSimCountryIso", identity.simCountryIso);
            hookCarrierMethod("getNetworkCountryIso", identity.networkCountryIso);
        }
    }

    private static void hookCarrierMethod(String methodName, String value) {
        if (value == null) return;
        try {
            XposedHelpers.findAndHookMethod(
                    TelephonyManager.class,
                    methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(value);
                        }
                    }
            );
        } catch (Throwable ignored) {}
    }
}
