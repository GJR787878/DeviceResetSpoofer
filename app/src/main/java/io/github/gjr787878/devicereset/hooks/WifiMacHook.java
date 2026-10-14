package io.github.gjr787878.devicereset.hooks;

import android.net.wifi.WifiInfo;

import java.net.NetworkInterface;

import io.github.gjr787878.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook WiFi MAC 地址。
 *
 * <p>覆盖三条读取路径：① {@link WifiInfo#getMacAddress()}；② {@link NetworkInterface#getHardwareAddress()}
 * （最常见的底层读 MAC 路径，旧版漏 hook）；③ {@link WifiInfo#getBSSID()}。</p>
 *
 * <p>关键修正：BSSID 是<b>接入点（路由器）</b>的 MAC，不是本机网卡 MAC。旧版错误地让 BSSID
 * 直接返回本机 MAC，一旦 App 做「本机 MAC == BSSID」一致性检查就会判异常。现从本机 MAC 派生一个
 * 稳定但不同的 BSSID（首字节翻转本地管理位），二者不再相等。</p>
 */
public class WifiMacHook {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, Identity identity) {
        if (identity.macAddress == null) return;
        final byte[] mac = macToBytes(identity.macAddress);
        final byte[] bssid = deriveBssid(mac);
        if (mac == null) return;

        // 1) WifiInfo.getMacAddress()
        try {
            XposedHelpers.findAndHookMethod(WifiInfo.class, "getMacAddress",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                param.setResult(identity.macAddress);
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] getMacAddress error: " + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] WifiMacHook getMacAddress install failed: " + t.getMessage());
        }

        // 2) NetworkInterface.getHardwareAddress()（wlan/eth 接口返回假 MAC，其他接口保持原样）
        try {
            XposedHelpers.findAndHookMethod(NetworkInterface.class, "getHardwareAddress",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                NetworkInterface ni = (NetworkInterface) param.thisObject;
                                String name = ni.getName();
                                if (name != null && (name.startsWith("wlan") || name.startsWith("eth"))) {
                                    param.setResult(mac.clone());
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] getHardwareAddress error: " + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] getHardwareAddress install failed: " + t.getMessage());
        }

        // 3) WifiInfo.getBSSID()：返回派生的接入点 MAC（与本机 MAC 不同）
        try {
            XposedHelpers.findAndHookMethod(WifiInfo.class, "getBSSID",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                param.setResult(bytesToMac(bssid));
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] getBSSID error: " + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable ignored) {}
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

    /** 从本机 MAC 派生稳定但不同的 BSSID：首字节翻转 bit1（本地管理位）。 */
    private static byte[] deriveBssid(byte[] mac) {
        byte[] b = mac.clone();
        b[0] ^= 0x02;
        // 极小概率翻转后仍相等（首字节无变化不可能，因异或 0x02 必改）；若整串仍相等则再翻首字节高位
        if (java.util.Arrays.equals(b, mac)) b[0] ^= 0x04;
        return b;
    }

    private static String bytesToMac(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
