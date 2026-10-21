package io.github.gjr787878.devicereset.hooks;

import io.github.gjr787878.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook Widevine DRM 设备唯一 ID（硬件级指纹）。
 *
 * <p>MediaDrm 的 {@code deviceUniqueId} 是由 DRM 厂商（Widevine/TA）在硬件信任区生成的
 * 设备唯一标识，clean flash ROM 才会重置，Java 层 Build hook 完全不覆盖。</p>
 *
 * <p>拦截路径：① {@code MediaDrm.getPropertyByteArray("deviceUniqueId")}；
 * ② {@code MediaDrm.getPropertyString} 中可能携带标识的属性（直接放行版本类字段，
 * 仅确保唯一 ID 走 byte array 路径）。</p>
 */
public class MediaDrmHook {

    /** Widevine UUID 对应的 property 名。 */
    private static final String PROP_DEVICE_UNIQUE_ID = "deviceUniqueId";

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, final Identity identity) {
        if (identity.drmId == null) return;

        // 1) getPropertyByteArray("deviceUniqueId")
        try {
            Class<?> mediaDrmClass = XposedHelpers.findClass(
                    "android.media.MediaDrm", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(mediaDrmClass, "getPropertyByteArray",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String prop = (String) param.args[0];
                                if (PROP_DEVICE_UNIQUE_ID.equals(prop)) {
                                    param.setResult(hexToBytes(identity.drmId));
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[DeviceReset] getPropertyByteArray error: "
                                        + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] MediaDrmHook getPropertyByteArray install failed: "
                    + t.getMessage());
        }

        // 2) getPropertyString：只拦截可能唯一标识的字段（deviceUniqueId 在部分版本
        //    也可通过 string 读取），其余版本类字段原样返回。
        try {
            Class<?> mediaDrmClass = XposedHelpers.findClass(
                    "android.media.MediaDrm", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(mediaDrmClass, "getPropertyString",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String prop = (String) param.args[0];
                                if (PROP_DEVICE_UNIQUE_ID.equals(prop)) {
                                    param.setResult(identity.drmId);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] MediaDrmHook getPropertyString install failed: "
                    + t.getMessage());
        }
    }

    /** hex 字符串还原为 byte[]。 */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] b = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            b[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return b;
    }
}
