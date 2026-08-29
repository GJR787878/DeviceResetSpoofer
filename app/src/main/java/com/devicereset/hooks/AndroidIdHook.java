package com.devicereset.hooks;

import android.content.ContentResolver;
import android.provider.Settings;

import com.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook Android ID (SSAID)。
 * 拦截 Settings.Secure.getString()，当查询的是 android_id 时返回伪装值。
 */
public class AndroidIdHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, Identity identity) {
        if (identity.androidId == null) return;

        // Hook Settings.Secure.getString(ContentResolver, String)
        XposedHelpers.findAndHookMethod(
                Settings.Secure.class,
                "getString",
                ContentResolver.class,
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String name = (String) param.args[1];
                        if ("android_id".equals(name)) {
                            param.setResult(identity.androidId);
                        }
                    }
                }
        );

        // Android 8.0+ 也可能通过 Settings.Secure.getStringForUser 访问
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
                            String name = (String) param.args[1];
                            if ("android_id".equals(name)) {
                                param.setResult(identity.androidId);
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
            // 某些ROM没有这个方法，忽略
        }
    }
}
