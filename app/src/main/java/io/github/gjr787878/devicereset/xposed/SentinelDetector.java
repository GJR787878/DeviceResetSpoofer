package io.github.gjr787878.devicereset.xposed;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;

/**
 * 哨兵文件检测器 —— 整个模块的核心。
 *
 * 原理：
 * - 在目标APP的私有目录写入一个隐藏标记文件 .identity_sentinel，内容为当前身份的JSON。
 * - 每次目标APP冷启动时，在最早的hook点检查这个文件是否存在。
 * - 文件不存在 → 用户刚清除了应用数据（清数据会删除整个私有目录）→ 生成全新身份。
 * - 文件存在 → 读取并沿用旧身份。
 */
public class SentinelDetector {
    private static final String SENTINEL_FILE = ".identity_sentinel";
    private static Identity cachedIdentity = null;
    private static boolean checked = false;

    public static Identity checkAndGetIdentity(Context context) {
        String internalDir = context.getFilesDir().getAbsolutePath();
        // 外部存储备份路径：/sdcard/Android/data/<目标包名>/files/
        // 目标APP进程有权限写自己的外部目录，UI端可通过root读取
        String externalDir = null;
        try {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) externalDir = ext.getAbsolutePath();
        } catch (Throwable ignored) {}
        return checkAndGetIdentityByDirs(internalDir, externalDir);
    }

    public static Identity checkAndGetIdentityByDir(String filesDirPath) {
        return checkAndGetIdentityByDirs(filesDirPath, null);
    }

    public static Identity checkAndGetIdentityByDirs(String internalDirPath, String externalDirPath) {
        if (checked && cachedIdentity != null) {
            return cachedIdentity;
        }
        XposedBridge.log("[DeviceReset] SentinelDetector internal=" + internalDirPath + " external=" + externalDirPath);
        try {
            File filesDir = new File(internalDirPath);
            File sentinel = new File(filesDir, SENTINEL_FILE);
            XposedBridge.log("[DeviceReset] sentinel path=" + sentinel.getAbsolutePath() + " exists=" + sentinel.exists());

            if (sentinel.exists()) {
                try {
                    FileInputStream fis = new FileInputStream(sentinel);
                    byte[] buffer = new byte[(int) sentinel.length()];
                    int read = fis.read(buffer);
                    fis.close();
                    String json = new String(buffer, 0, read, StandardCharsets.UTF_8);
                    Identity id = Identity.fromJson(json);
                    if (id != null && id.androidId != null) {
                        cachedIdentity = id;
                        checked = true;
                        XposedBridge.log("[DeviceReset] 读取到已有身份 androidId=" + id.androidId);
                        // 同步备份到外部存储
                        if (externalDirPath != null) {
                            writeBackup(externalDirPath, json);
                        }
                        return id;
                    }
                    XposedBridge.log("[DeviceReset] 哨兵文件存在但解析失败，重新生成");
                } catch (Throwable readErr) {
                    XposedBridge.log("[DeviceReset] 读取哨兵文件失败: " + readErr.getMessage());
                }
            }

            // 哨兵不存在或读取失败 → 生成全新身份
            Identity newIdentity = IdentityGenerator.generateRandom();
            String jsonStr = newIdentity.toJson();
            if (!filesDir.exists()) {
                boolean mk = filesDir.mkdirs();
                XposedBridge.log("[DeviceReset] mkdirs " + filesDir.getAbsolutePath() + " result=" + mk);
            }

            // 用FileOutputStream写入内部目录（比Files.write更兼容）
            boolean internalOk = writeFile(sentinel, jsonStr);
            XposedBridge.log("[DeviceReset] 内部目录写入 " + (internalOk ? "成功" : "失败") + " " + sentinel.getAbsolutePath());

            // 同时写入外部存储备份（UI端可读）
            if (externalDirPath != null) {
                boolean extOk = writeBackup(externalDirPath, jsonStr);
                XposedBridge.log("[DeviceReset] 外部备份写入 " + (extOk ? "成功" : "失败") + " " + externalDirPath);
            }

            // 尝试设置全局可读
            try {
                Runtime.getRuntime().exec(new String[]{"chmod", "666", sentinel.getAbsolutePath()}).waitFor();
            } catch (Throwable ignored) {}

            cachedIdentity = newIdentity;
            checked = true;
            XposedBridge.log("[DeviceReset] 生成新身份 androidId=" + newIdentity.androidId + " model=" + newIdentity.model);
            return newIdentity;
        } catch (Throwable t) {
            XposedBridge.log("[DeviceReset] SentinelDetector异常: " + t.getMessage());
            if (cachedIdentity == null) {
                cachedIdentity = IdentityGenerator.generateRandom();
            }
            checked = true;
            return cachedIdentity;
        }
    }

    /** 用FileOutputStream写入文件，返回是否成功 */
    private static boolean writeFile(File target, String content) {
        try {
            FileOutputStream fos = new FileOutputStream(target);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
            return target.exists() && target.length() > 0;
        } catch (Throwable e) {
            XposedBridge.log("[DeviceReset] FileOutputStream写入失败 " + target.getAbsolutePath() + ": " + e.getMessage());
            // 兜底：用sh -c写入
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                        "cat > '" + target.getAbsolutePath() + "' << 'DRS_EOF'\n" + content + "\nDRS_EOF\n"});
                p.waitFor();
                boolean ok = target.exists() && target.length() > 0;
                XposedBridge.log("[DeviceReset] sh兜底写入 " + (ok ? "成功" : "失败") + " exit=" + p.exitValue());
                return ok;
            } catch (Throwable e2) {
                XposedBridge.log("[DeviceReset] sh写入也失败: " + e2.getMessage());
                return false;
            }
        }
    }

    /** 写入外部存储备份 */
    private static boolean writeBackup(String externalDirPath, String json) {
        try {
            File extDir = new File(externalDirPath);
            if (!extDir.exists()) extDir.mkdirs();
            File backup = new File(extDir, SENTINEL_FILE);
            return writeFile(backup, json);
        } catch (Throwable e) {
            XposedBridge.log("[DeviceReset] 外部备份异常: " + e.getMessage());
            return false;
        }
    }

    public static boolean resetIdentity(String packageName, Context context) {
        try {
            Context targetContext = context.createPackageContext(packageName,
                    Context.MODE_PRIVATE | Context.CONTEXT_IGNORE_SECURITY);
            File sentinel = new File(targetContext.getFilesDir(), SENTINEL_FILE);
            if (sentinel.exists()) {
                sentinel.delete();
            }
            cachedIdentity = null;
            checked = false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Identity getCurrentIdentity() {
        return cachedIdentity;
    }
}
