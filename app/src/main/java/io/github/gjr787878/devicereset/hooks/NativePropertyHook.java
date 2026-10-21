package io.github.gjr787878.devicereset.hooks;

import java.util.ArrayList;
import java.util.List;

import io.github.gjr787878.devicereset.xposed.Identity;

import de.robv.android.xposed.XposedBridge;

/**
 * Native 系统属性 hook（Java 侧入口）。
 *
 * <p>App 通过 JNI/native 代码直接调用 {@code __system_property_get} 读取
 * ro.serialno、ro.build.fingerprint 等属性时，Java 层 Build hook 完全无效。
 * 本类加载 native 库，由 PLT hook 改写各已加载 .so 的 GOT，并把 Identity 的
 * 伪装值同步到 native 属性映射表（30+ 属性名）。</p>
 */
public class NativePropertyHook {

    private static volatile boolean sLoaded = false;
    private static volatile boolean sInstalled = false;

    static {
        try {
            System.loadLibrary("devicereset_native");
            sLoaded = true;
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] native lib load failed: " + t.getMessage());
        }
    }

    /** 安装 PLT hook（首次）。 */
    public static synchronized void install() {
        if (!sLoaded) return;
        if (!sInstalled) {
            try {
                int n = nativeInstall();
                sInstalled = true;
                XposedBridge.log("[DeviceReset] native PLT hook installed, scanned=" + n);
            } catch (Throwable t) {
                XposedBridge.log("[DeviceReset] nativeInstall failed: " + t.getMessage());
            }
        }
    }

    /** 同步身份到 native 映射表（同时重新扫描，覆盖新加载的 so）。 */
    public static void syncIdentity(Identity id) {
        if (!sLoaded || id == null) return;
        install();
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();

        add(names, values, "ro.product.brand", id.brand);
        add(names, values, "ro.product.model", id.model);
        add(names, values, "ro.product.manufacturer", id.manufacturer);
        add(names, values, "ro.product.device", id.device);
        add(names, values, "ro.product.name", id.product);
        // 多分区变体（Android 11+ 分区化属性）
        for (String part : new String[]{"system", "vendor", "product", "odm", "system_ext"}) {
            add(names, values, "ro.product." + part + ".brand", id.brand);
            add(names, values, "ro.product." + part + ".model", id.model);
            add(names, values, "ro.product." + part + ".manufacturer", id.manufacturer);
            add(names, values, "ro.product." + part + ".device", id.device);
            add(names, values, "ro.product." + part + ".name", id.product);
        }
        add(names, values, "ro.hardware", id.hardware);
        add(names, values, "ro.build.fingerprint", id.fingerprint);
        add(names, values, "ro.bootimage.build.fingerprint", id.fingerprint);
        add(names, values, "ro.odm.build.fingerprint", id.fingerprint);
        add(names, values, "ro.system.build.fingerprint", id.fingerprint);
        add(names, values, "ro.vendor.build.fingerprint", id.fingerprint);
        add(names, values, "ro.build.id", id.buildId);
        add(names, values, "ro.build.display.id", id.buildDisplay);
        add(names, values, "ro.build.tags", id.buildTags);
        add(names, values, "ro.build.type", id.buildType);
        add(names, values, "ro.build.user", id.buildUser);
        add(names, values, "ro.build.host", id.buildHost);
        add(names, values, "ro.bootloader", id.bootloader);
        add(names, values, "ro.bootloader.version", id.bootloader);
        // 序列号
        add(names, values, "ro.serialno", id.serial);
        add(names, values, "ro.boot.serialno", id.serial);
        add(names, values, "ro.ril.serialno", id.serial);
        // 运营商
        add(names, values, "gsm.sim.operator.numeric", id.simOperator);
        add(names, values, "gsm.sim.operator.alpha", id.simOperatorName);
        add(names, values, "gsm.sim.operator.iso-country", id.simCountryIso);
        add(names, values, "gsm.operator.numeric", id.networkOperator);
        add(names, values, "gsm.operator.alpha", id.networkOperatorName);
        add(names, values, "gsm.operator.iso-country", id.networkCountryIso);
        // 硬件 MAC
        add(names, values, "ro.boot.wifimacaddr", id.macAddress);
        add(names, values, "ro.boot.btmacaddr", id.bluetoothMac);

        try {
            nativeSync(names.toArray(new String[0]), values.toArray(new String[0]));
            XposedBridge.log("[DeviceReset] native identity synced, props=" + names.size());
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] nativeSync failed: " + t.getMessage());
        }
    }

    private static void add(List<String> names, List<String> values, String name, String value) {
        if (value == null || value.isEmpty()) return;
        names.add(name);
        values.add(value);
    }

    // ===================== native 方法 =====================
    private static native int nativeInstall();
    private static native void nativeSync(String[] names, String[] values);
}
