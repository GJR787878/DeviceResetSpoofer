package com.devicereset.hooks;

import android.net.wifi.WifiInfo;

import com.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook WiFi MAC地址。
 * Android 6.0+ 默认返回 02:00:00:00:00:00，但仍有APP通过其他方式获取。
 */
public class WifiMacHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, Identity identity) {
        if (identity.macAddress == null) return;

        // Hook WifiInfo.getMacAddress()
        try {
            XposedHelpers.findAndHookMethod(
                    WifiInfo.class,
                    "getMacAddress",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(identity.macAddress);
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // Hook WifiInfo.getBSSID() (返回AP的MAC，也伪装成一致的格式)
        try {
            XposedHelpers.findAndHookMethod(
                    WifiInfo.class,
                    "getBSSID",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // BSSID用不同的随机MAC
                            param.setResult(identity.macAddress);
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // 通过反射读取 /sys/class/net/wlan0/address 的方式
        // 部分APP会直接读这个文件，需要hook java.io.FileInputStream或Runtime.exec
        try {
            XposedHelpers.findAndHookMethod(
                    "java.io.FileInputStream",
                    lpparam.classLoader,
                    "<init>",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String path = (String) param.args[0];
                            if (path != null && path.contains("/net/") && path.endsWith("/address")) {
                                // 不阻止读取，但在读取后替换内容比较复杂
                                // 这里简单处理：让它抛出FileNotFoundException，APP会fallback到getMacAddress()
                                param.setThrowable(new java.io.FileNotFoundException(path));
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}
    }
}
