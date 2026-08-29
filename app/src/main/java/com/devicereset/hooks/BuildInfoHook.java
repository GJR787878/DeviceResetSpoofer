package com.devicereset.hooks;

import android.os.Build;

import com.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook Build 信息：型号、品牌、厂商、设备名、指纹等。
 * 通过反射修改Build类的静态字段实现。
 */
public class BuildInfoHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, Identity identity) {
        // 修改 Build 类的静态字段
        setStaticFieldIfNotNull("BRAND", identity.brand);
        setStaticFieldIfNotNull("MODEL", identity.model);
        setStaticFieldIfNotNull("MANUFACTURER", identity.manufacturer);
        setStaticFieldIfNotNull("DEVICE", identity.device);
        setStaticFieldIfNotNull("PRODUCT", identity.product);
        setStaticFieldIfNotNull("HARDWARE", identity.hardware);
        setStaticFieldIfNotNull("FINGERPRINT", identity.fingerprint);
        setStaticFieldIfNotNull("BOOTLOADER", identity.bootloader);
        setStaticFieldIfNotNull("RADIO", identity.radioVersion);
        setStaticFieldIfNotNull("ID", identity.buildId);

        // Build.TIME 是 long 类型
        if (identity.buildTime != null) {
            try {
                XposedHelpers.setStaticObjectField(Build.class, "TIME",
                        Long.parseLong(identity.buildTime));
            } catch (Throwable ignored) {}
        }

        // Hook Build.getRadioVersion()
        try {
            XposedHelpers.findAndHookMethod(
                    Build.class,
                    "getRadioVersion",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (identity.radioVersion != null) {
                                param.setResult(identity.radioVersion);
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // Hook Build.getSerial() (Android 8.0+)
        try {
            XposedHelpers.findAndHookMethod(
                    Build.class,
                    "getSerial",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (identity.serial != null) {
                                param.setResult(identity.serial);
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // Build.SERIAL 静态字段 (Android 7.1及以下)
        if (identity.serial != null) {
            try {
                XposedHelpers.setStaticObjectField(Build.class, "SERIAL", identity.serial);
            } catch (Throwable ignored) {}
        }

        // Hook Build.VERSION 相关字段（可选，保持系统版本不变更安全）
        // 这里不修改SDK_INT等，避免APP因版本不匹配而崩溃
    }

    private static void setStaticFieldIfNotNull(String fieldName, String value) {
        if (value == null) return;
        try {
            XposedHelpers.setStaticObjectField(Build.class, fieldName, value);
        } catch (Throwable ignored) {
            // 字段不存在或不可修改，忽略
        }
    }
}
