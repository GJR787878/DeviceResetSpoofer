package io.github.gjr787878.devicereset;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 模块配置管理。
 * 配置存在模块自己的SharedPreferences中，不在目标APP目录，
 * 所以清除目标APP数据不会影响模块配置。
 */
public class Config {
    private static final String PREFS_NAME = "devicereset_config";
    private static final String KEY_TARGET_PACKAGES = "target_packages";
    private static final String KEY_AUTO_RESET = "auto_reset_on_clear";
    private static final String KEY_HOOK_ANDROID_ID = "hook_android_id";
    private static final String KEY_HOOK_AD_ID = "hook_ad_id";
    private static final String KEY_HOOK_IMEI = "hook_imei";
    private static final String KEY_HOOK_BUILD = "hook_build_info";
    private static final String KEY_HOOK_MAC = "hook_mac";
    private static final String KEY_HOOK_GSF = "hook_gsf_id";
    private static final String KEY_HOOK_CARRIER = "hook_carrier";

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 获取所有目标应用包名。
     * 注意：在Xposed hook进程中调用时，context是目标APP的context，
     * 无法直接读取模块的SharedPreferences。所以目标包名列表通过
     * XSharedPreferences读取，这里提供给配置界面使用。
     */
    public static Set<String> getTargetPackages(Context context) {
        return getPrefs(context).getStringSet(KEY_TARGET_PACKAGES, new HashSet<>());
    }

    public static void setTargetPackages(Context context, Set<String> packages) {
        getPrefs(context).edit().putStringSet(KEY_TARGET_PACKAGES, packages).apply();
    }

    public static void addTargetPackage(Context context, String packageName) {
        Set<String> packages = new HashSet<>(getTargetPackages(context));
        packages.add(packageName);
        setTargetPackages(context, packages);
    }

    public static void removeTargetPackage(Context context, String packageName) {
        Set<String> packages = new HashSet<>(getTargetPackages(context));
        packages.remove(packageName);
        setTargetPackages(context, packages);
    }

    public static boolean isTargetPackage(Context context, String packageName) {
        return getTargetPackages(context).contains(packageName);
    }

    // 各开关的getter/setter
    public static boolean isAutoReset(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTO_RESET, true);
    }

    public static void setAutoReset(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_RESET, value).apply();
    }

    public static boolean isHookAndroidId(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_ANDROID_ID, true);
    }

    public static void setHookAndroidId(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_ANDROID_ID, value).apply();
    }

    public static boolean isHookAdId(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_AD_ID, true);
    }

    public static void setHookAdId(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_AD_ID, value).apply();
    }

    public static boolean isHookImei(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_IMEI, true);
    }

    public static void setHookImei(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_IMEI, value).apply();
    }

    public static boolean isHookBuild(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_BUILD, true);
    }

    public static void setHookBuild(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_BUILD, value).apply();
    }

    public static boolean isHookMac(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_MAC, true);
    }

    public static void setHookMac(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_MAC, value).apply();
    }

    public static boolean isHookGsf(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_GSF, true);
    }

    public static void setHookGsf(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_GSF, value).apply();
    }

    public static boolean isHookCarrier(Context context) {
        return getPrefs(context).getBoolean(KEY_HOOK_CARRIER, true);
    }

    public static void setHookCarrier(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(KEY_HOOK_CARRIER, value).apply();
    }
}
