package io.github.gjr787878.devicereset.hooks;

import android.content.ContentResolver;
import android.provider.Settings;

import io.github.gjr787878.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook Settings.Global / Settings.System 读取的标识符。
 *
 * <p>拦截 {@code Settings.Global.getString} 与 {@code Settings.System.getString}：
 * 覆盖 {@code device_name}（设备名）、{@code bluetooth_address}、{@code bluetooth_name}
 * 等通过 Settings 读取的标识。{@code android_id} 已由 {@link AndroidIdHook} 覆盖。</p>
 */
public class SettingsGlobalHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, final Identity identity) {
        // 1) Settings.Global.getString
        try {
            XposedHelpers.findAndHookMethod(
                    Settings.Global.class,
                    "getString",
                    ContentResolver.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            rewrite(identity, param);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] SettingsGlobalHook Global getString install failed: "
                    + t.getMessage());
        }

        // 2) Settings.Global.getStringForUser
        try {
            XposedHelpers.findAndHookMethod(
                    Settings.Global.class,
                    "getStringForUser",
                    ContentResolver.class,
                    String.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            rewrite(identity, param);
                        }
                    });
        } catch (Throwable ignored) {}

        // 3) Settings.System.getString（部分 ROM 把 device_name 放 System）
        try {
            XposedHelpers.findAndHookMethod(
                    Settings.System.class,
                    "getString",
                    ContentResolver.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            rewrite(identity, param);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] SettingsGlobalHook System getString install failed: "
                    + t.getMessage());
        }
    }

    /** 按 key 决定返回值。 */
    private static void rewrite(Identity identity, XC_MethodHook.MethodHookParam param) {
        try {
            if (param.args == null || param.args.length < 2) return;
            String key = (String) param.args[1];
            if (key == null) return;
            switch (key) {
                case "device_name":
                case "bluetooth_name":
                    if (identity.model != null) param.setResult(identity.model);
                    break;
                case "bluetooth_address":
                    if (identity.bluetoothMac != null) param.setResult(identity.bluetoothMac);
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {}
    }
}
