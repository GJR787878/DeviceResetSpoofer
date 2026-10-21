package io.github.gjr787878.devicereset.hooks;

import io.github.gjr787878.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook 传感器指纹。
 *
 * <p>传感器型号/厂商/版本可作为设备指纹（同型号设备传感器批次一致）。
 * 拦截 {@code Sensor.getName()}、{@code Sensor.getVendor()}、{@code Sensor.getVersion()}。</p>
 */
public class SensorHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, final Identity identity) {
        Class<?> sensorClass;
        try {
            sensorClass = XposedHelpers.findClass("android.hardware.Sensor", lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] SensorHook Sensor class not found: " + t.getMessage());
            return;
        }

        // 1) getName()
        try {
            XposedHelpers.findAndHookMethod(sensorClass, "getName",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (identity.sensorName != null) {
                                param.setResult(identity.sensorName);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] SensorHook getName install failed: " + t.getMessage());
        }

        // 2) getVendor()
        try {
            XposedHelpers.findAndHookMethod(sensorClass, "getVendor",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (identity.sensorVendor != null) {
                                param.setResult(identity.sensorVendor);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] SensorHook getVendor install failed: " + t.getMessage());
        }

        // 3) getVersion()（int）
        try {
            XposedHelpers.findAndHookMethod(sensorClass, "getVersion",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (identity.sensorVersion != null) {
                                try {
                                    param.setResult(Integer.parseInt(identity.sensorVersion));
                                } catch (Throwable ignored) {}
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] SensorHook getVersion install failed: " + t.getMessage());
        }
    }
}
