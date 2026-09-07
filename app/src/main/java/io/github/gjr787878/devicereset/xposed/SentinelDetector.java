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
        return checkAndGetIdentityByDir(context.getFilesDir().getAbsolutePath());
    }

    public static Identity checkAndGetIdentityByDir(String filesDirPath) {
        if (checked && cachedIdentity != null) {
            return cachedIdentity;
        }
        XposedBridge.log("[DeviceReset] SentinelDetector check path=" + filesDirPath);
        try {
            File filesDir = new File(filesDirPath);
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
                        return id;
                    }
                    XposedBridge.log("[DeviceReset] 哨兵文件存在但解析失败，重新生成");
                } catch (Throwable readErr) {
                    XposedBridge.log("[DeviceReset] 读取哨兵文件失败: " + readErr.getMessage());
                }
            }

            // 哨兵不存在或读取失败 → 生成全新身份
            Identity newIdentity = IdentityGenerator.generateRandom();
            if (!filesDir.exists()) {
                boolean mk = filesDir.mkdirs();
                XposedBridge.log("[DeviceReset] mkdirs " + filesDir.getAbsolutePath() + " result=" + mk);
            }

            // 用FileOutputStream写入（比Files.write更兼容）
            try {
                FileOutputStream fos = new FileOutputStream(sentinel);
                fos.write(newIdentity.toJson().getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.close();
                XposedBridge.log("[DeviceReset] 写入哨兵成功 " + sentinel.getAbsolutePath() + " length=" + sentinel.length());
            } catch (Throwable writeErr) {
                XposedBridge.log("[DeviceReset] FileOutputStream写入失败: " + writeErr.getMessage());
                // 兜底：用Runtime.exec写入
                try {
                    String json = newIdentity.toJson();
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                            "echo '" + json.replace("'", "'\\''") + "' > '" + sentinel.getAbsolutePath() + "'"});
                    p.waitFor();
                    XposedBridge.log("[DeviceReset] sh写入结果 exit=" + p.exitValue() + " exists=" + sentinel.exists());
                } catch (Throwable execErr) {
                    XposedBridge.log("[DeviceReset] sh写入也失败: " + execErr.getMessage());
                }
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
