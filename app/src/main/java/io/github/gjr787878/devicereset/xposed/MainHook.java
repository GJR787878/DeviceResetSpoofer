package io.github.gjr787878.devicereset.xposed;

import android.content.pm.ApplicationInfo;

import io.github.gjr787878.devicereset.hooks.AdvertisingIdHook;
import io.github.gjr787878.devicereset.hooks.AndroidIdHook;
import io.github.gjr787878.devicereset.hooks.BuildInfoHook;
import io.github.gjr787878.devicereset.hooks.GsfIdHook;
import io.github.gjr787878.devicereset.hooks.TelephonyHook;
import io.github.gjr787878.devicereset.hooks.WifiMacHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed模块主入口。
 *
 * 直接对所有LSPosed作用域中的应用生效，无需在模块内再次选择应用。
 * 通过反射获取当前ActivityThread的mBoundApplication.appInfo.dataDir，
 * 立刻执行哨兵检测并安装所有设备ID Hook。
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String MODULE_PACKAGE = "io.github.gjr787878.devicereset";
    private static final String PREFS_NAME = "devicereset_config";
    private static XSharedPreferences xPrefs;
    private static boolean prefsAvailable = false;
    /** 目标列表缓存：从模块 files/targets.txt 读取（chmod 666），不依赖 XSharedPreferences */
    private static volatile java.util.Set<String> cachedTargets = null;
    private static long lastTargetsRead = 0L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (MODULE_PACKAGE.equals(lpparam.packageName)) return;

        // 注入探测：本进程被 LSPosed 注入即写 probe 日志（证明全局注入是否覆盖所有进程）
        writeProbeLog(lpparam.packageName);

        // 全局注入模式：模块在 LSPosed 勾「系统框架」后注入所有进程。
        // 不再用 targets.txt 过滤——目标进程(其他 UID+SELinux appdomain)读不到模块私有目录，
        // 这是此前「注入了却不生效」的根因（HOOK 空、probe 有）。
        // 改为哨兵驱动：只有「手动写入过伪装值」的应用（自己目录有 .identity_sentinel，chmod 666）
        // 才安装 Hook；其余进程快速跳过，零开销。

        // 读取模块配置（XSharedPreferences 兜底：开关与身份）
        if (xPrefs == null) {
            try {
                xPrefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
                xPrefs.makeWorldReadable();
                prefsAvailable = true;
            } catch (Throwable t) {
                XposedBridge.log("[DeviceReset] XSharedPreferences init failed: " + t.getMessage());
                prefsAvailable = false;
            }
        }
        if (prefsAvailable) {
            try { xPrefs.reload(); } catch (Throwable ignored) {}
        }

        // 读取各Hook开关
        final boolean hookAndroidId = getPrefBoolean("hook_android_id", true);
        final boolean hookAdId = getPrefBoolean("hook_ad_id", true);
        final boolean hookImei = getPrefBoolean("hook_imei", true);
        final boolean hookBuild = getPrefBoolean("hook_build_info", true);
        final boolean hookMac = getPrefBoolean("hook_mac", true);
        final boolean hookGsf = getPrefBoolean("hook_gsf_id", true);
        final boolean hookCarrier = getPrefBoolean("hook_carrier", true);

        try {
            // 通过反射获取当前进程的dataDir
            String filesDir = getDataDir(lpparam.packageName);
            if (filesDir == null) {
                XposedBridge.log("[DeviceReset] ERROR: cannot get dataDir for " + lpparam.packageName);
                return;
            }
            XposedBridge.log("[DeviceReset] dataDir resolved: " + filesDir);

            // 外部存储目录：/sdcard/Android/data/<包名>/files（应用自身UID可写，UI可root读）
            String externalFilesDir = null;
            try {
                externalFilesDir = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                        "Android/data/" + lpparam.packageName + "/files").getAbsolutePath();
            } catch (Throwable ignored) {}
            XposedBridge.log("[DeviceReset] externalFilesDir: " + externalFilesDir);

            // 身份读取优先级：哨兵文件（手动触发写入，chmod 666，最可靠）→ 模块配置（XSharedPreferences 兜底）
            Identity identity = null;
            boolean identityFromSentinel = false;
            try {
                identity = SentinelDetector.checkAndGetIdentityByDirs(filesDir, externalFilesDir);
            } catch (Throwable ignored) {}
            if (identity != null) {
                identityFromSentinel = true;
                XposedBridge.log("[DeviceReset] Identity loaded from sentinel: androidId=" + identity.androidId
                        + ", model=" + identity.model + ", brand=" + identity.brand);
            } else {
                String idJson = getPrefString("identity_" + lpparam.packageName);
                if (idJson != null && idJson.startsWith("{")) {
                    try {
                        identity = Identity.fromJson(idJson);
                        if (identity != null) {
                            XposedBridge.log("[DeviceReset] Identity loaded from module config: androidId=" + identity.androidId
                                    + ", model=" + identity.model + ", brand=" + identity.brand);
                        }
                    } catch (Throwable parseErr) {
                        XposedBridge.log("[DeviceReset] parse module-config identity failed: " + parseErr.getMessage());
                    }
                }
            }
            if (identity == null) {
                // 无哨兵 = 未手动写入伪装值 → 不安装任何 Hook（零开销跳过）
                XposedBridge.log("[DeviceReset] no identity for " + lpparam.packageName
                        + ", skip hooks (identity must be manually triggered in module UI)");
                return;
            }

            // 安装所有Hook
            if (hookAndroidId) {
                AndroidIdHook.install(lpparam, identity);
                XposedBridge.log("[DeviceReset] AndroidId hook installed");
            }
            if (hookAdId) {
                AdvertisingIdHook.install(lpparam, identity);
                XposedBridge.log("[DeviceReset] AdvertisingId hook installed");
            }
            if (hookImei || hookCarrier) {
                TelephonyHook.install(lpparam, identity, hookImei, hookCarrier);
                XposedBridge.log("[DeviceReset] Telephony hook installed");
            }
            if (hookBuild) {
                BuildInfoHook.install(lpparam, identity);
                XposedBridge.log("[DeviceReset] BuildInfo hook installed");
            }
            if (hookMac) {
                WifiMacHook.install(lpparam, identity);
                XposedBridge.log("[DeviceReset] WifiMac hook installed");
            }
            if (hookGsf) {
                GsfIdHook.install(lpparam, identity);
                XposedBridge.log("[DeviceReset] GsfId hook installed");
            }

            XposedBridge.log("[DeviceReset] ALL hooks installed successfully for " + lpparam.packageName);
            writeHookLog(lpparam.packageName, "ALL hooks installed, identity source="
                    + (identityFromSentinel ? "sentinel" : "module-config"));
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] FATAL error: " + t.getMessage());
            XposedBridge.log(t);
        }
    }

    /** 注入探测：任何进程被 LSPosed 注入都写一行（证明全局注入覆盖所有进程）。
     * 写在进程自身外部目录，导出日志扫描 .drs_probe.log 即可确认。 */
    private void writeProbeLog(String pkg) {
        try {
            java.io.File ext = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                    "Android/data/" + pkg + "/files");
            ext.mkdirs();
            java.io.File f = new java.io.File(ext, ".drs_probe.log");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
            fos.write(("probe ts=" + System.currentTimeMillis() + " pkg=" + pkg + " injected\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /** 在目标应用的外部目录写注入证据日志（目标进程有权限写自己包名的外部目录）。
     * 目的：导出日志时可确认「真授权」——MainHook 是否真的在目标进程执行、身份是否命中。 */
    private void writeHookLog(String pkg, String msg) {
        try {
            java.io.File ext = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                    "Android/data/" + pkg + "/files");
            ext.mkdirs();
            java.io.File f = new java.io.File(ext, ".drs_hook.log");
            StringBuilder sb = new StringBuilder();
            sb.append("time=").append(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date())).append(" pkg=").append(pkg).append(" ").append(msg).append('\n');
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 获取当前进程的filesDir路径。
     */
    private String getDataDir(String packageName) {
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            if (activityThread != null) {
                Object boundData = XposedHelpers.getObjectField(activityThread, "mBoundApplication");
                if (boundData != null) {
                    ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.getObjectField(boundData, "appInfo");
                    if (appInfo != null && appInfo.dataDir != null) {
                        return appInfo.dataDir + "/files";
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] get dataDir via ActivityThread failed: " + t.getMessage());
        }
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object app = XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication");
            if (app != null) {
                java.io.File filesDir = (java.io.File) XposedHelpers.callMethod(app, "getFilesDir");
                if (filesDir != null) {
                    return filesDir.getAbsolutePath();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] get dataDir via Application failed: " + t.getMessage());
        }
        return "/data/data/" + packageName + "/files";
    }

    private boolean getPrefBoolean(String key, boolean defaultValue) {
        if (!prefsAvailable || xPrefs == null) return defaultValue;
        try {
            return xPrefs.getBoolean(key, defaultValue);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private java.util.Set<String> getPrefStringSet(String key) {
        if (!prefsAvailable || xPrefs == null) return null;
        try {
            java.util.Set<String> s = xPrefs.getStringSet(key, null);
            return s != null ? new java.util.HashSet<>(s) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String getPrefString(String key) {
        if (!prefsAvailable || xPrefs == null) return null;
        try {
            return xPrefs.getString(key, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读取目标列表：模块 files/targets.txt（每行一个包名，chmod 644）。
     * 带 2 秒缓存，避免每个 app 启动都读盘。
     */
    private java.util.Set<String> readTargetsFile() {
        long now = System.currentTimeMillis();
        if (cachedTargets != null && now - lastTargetsRead < 2000L) return cachedTargets;
        java.util.Set<String> result = null;
        try {
            java.io.File f = new java.io.File("/data/data/" + MODULE_PACKAGE + "/files/targets.txt");
            if (f.exists()) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath());
                result = new java.util.HashSet<>();
                for (String l : lines) {
                    l = l.trim();
                    if (!l.isEmpty()) result.add(l);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] readTargetsFile failed: " + t.getMessage());
        }
        cachedTargets = result;
        lastTargetsRead = now;
        return result;
    }
}
