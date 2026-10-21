package io.github.gjr787878.devicereset.hooks;

import java.net.NetworkInterface;

import io.github.gjr787878.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook 蓝牙 MAC 地址。
 *
 * <p>原模块只 hook 了 WiFi MAC，蓝牙 MAC 未覆盖。覆盖路径：
 * ① {@code BluetoothAdapter.getAddress()}；② 反射修改 {@code BluetoothAdapter.mAddress}
 * 字段（部分 App 直接反射读取）；③ {@code NetworkInterface.getHardwareAddress()} 对
 * bt 接口返回假值。</p>
 */
public class BluetoothMacHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, final Identity identity) {
        if (identity.bluetoothMac == null) return;
        final byte[] mac = macToBytes(identity.bluetoothMac);
        if (mac == null) return;

        // 1) BluetoothAdapter.getAddress()
        try {
            Class<?> adapterClass = XposedHelpers.findClass(
                    "android.bluetooth.BluetoothAdapter", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(adapterClass, "getAddress",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                param.setResult(identity.bluetoothMac);
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] bt getAddress error: "
                                        + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] BluetoothMacHook getAddress install failed: "
                    + t.getMessage());
        }

        // 2) 反射 mAddress 字段：直接改静态/实例字段，App 反射读取时拿到假值
        try {
            Class<?> adapterClass = XposedHelpers.findClass(
                    "android.bluetooth.BluetoothAdapter", lpparam.classLoader);
            try {
                // 对默认适配器实例的 mAddress 字段
                Object defaultAdapter = XposedHelpers.callStaticMethod(adapterClass,
                        "getDefaultAdapter");
                if (defaultAdapter != null) {
                    XposedHelpers.setObjectField(defaultAdapter, "mAddress",
                            identity.bluetoothMac);
                }
            } catch (Throwable ignored) {}
            // 同时 hook getAddress 后填充字段的内部路径：hook 字段读取较难，
            // 再 hook 反射友好的 IBluetooth.getAddress（若存在）
            Class<?> bluetoothClass = XposedHelpers.findClass(
                    "android.bluetooth.IBluetooth$Stub", lpparam.classLoader);
            // IBluetooth 是接口，实现类在系统侧；App 内拿不到，跳过。
        } catch (Throwable ignored) {}

        // 3) NetworkInterface.getHardwareAddress()：bt 接口返回假 MAC
        try {
            XposedHelpers.findAndHookMethod(NetworkInterface.class, "getHardwareAddress",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                NetworkInterface ni = (NetworkInterface) param.thisObject;
                                String name = ni.getName();
                                if (name != null && name.startsWith("bt")) {
                                    param.setResult(mac.clone());
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] bt getHardwareAddress error: "
                                        + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] BluetoothMacHook getHardwareAddress install failed: "
                    + t.getMessage());
        }
    }

    /** "12:93:7B:26:26:CC" → byte[6]。非法返回 null。 */
    private static byte[] macToBytes(String mac) {
        try {
            String[] parts = mac.split(":");
            if (parts.length != 6) return null;
            byte[] b = new byte[6];
            for (int i = 0; i < 6; i++) b[i] = (byte) Integer.parseInt(parts[i], 16);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }
}
