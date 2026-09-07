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
    /** 运行时值文件：只由模块在目标进程每次加载身份时写，记录进程当前真正在用的值（UI不写此文件） */
    private static final String RUNTIME_FILE = ".identity_runtime";
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
                            writeRuntime(externalDirPath, json);
                        }
                        return id;
                    }
                    XposedBridge.log("[DeviceReset] 哨兵文件存在但解析失败，重新生成");
                } catch (Throwable readErr) {
                    XposedBridge.log("[DeviceReset] 读取哨兵文件失败: " + readErr.getMessage());
                }
            }

            // 内部哨兵不存在或解析失败 → 先尝试从外部备份恢复（保证与UI显示值一致，不另生成新身份）
            if (externalDirPath != null) {
                try {
                    File extDir = new File(externalDirPath);
                    File extSentinel = new File(extDir, SENTINEL_FILE);
                    XposedBridge.log("[DeviceReset] 内部缺失，尝试外部备份 " + extSentinel.getAbsolutePath() + " exists=" + extSentinel.exists());
                    if (extSentinel.exists()) {
                        FileInputStream efis = new FileInputStream(extSentinel);
                        byte[] ebuf = new byte[(int) extSentinel.length()];
                        int eread = efis.read(ebuf);
                        efis.close();
                        String extJson = new String(ebuf, 0, eread, StandardCharsets.UTF_8);
                        Identity extId = Identity.fromJson(extJson);
                        if (extId != null && extId.androidId != null) {
                            // 外部备份有效 → 回写内部，保持两处一致
                            if (!filesDir.exists()) filesDir.mkdirs();
                            boolean restoreOk = writeFile(sentinel, extJson);
                            XposedBridge.log("[DeviceReset] 从外部备份恢复身份 " + (restoreOk ? "成功" : "失败") + " androidId=" + extId.androidId);
                            writeRuntime(externalDirPath, extJson);
                            cachedIdentity = extId;
                            checked = true;
                            return extId;
                        }
                        XposedBridge.log("[DeviceReset] 外部备份解析失败，将重新生成");
                    }
                } catch (Throwable extErr) {
                    XposedBridge.log("[DeviceReset] 读取外部备份异常: " + extErr.getMessage());
                }
            }

            // 内部、外部都没有 → 生成全新身份（自动触发，全链路记录）
            Identity newIdentity = IdentityGenerator.generateRandom();
            String jsonStr = newIdentity.toJson();
            XposedBridge.log("[DeviceReset] ===AUTO-GENERATE START=== androidId=" + newIdentity.androidId
                    + " model=" + newIdentity.model + " brand=" + newIdentity.brand);
            XposedBridge.log("[DeviceReset] AUTO-GENERATE fullJson=" + jsonStr);

            if (!filesDir.exists()) {
                boolean mk = filesDir.mkdirs();
                XposedBridge.log("[DeviceReset] AUTO-GENERATE mkdirs internal=" + filesDir.getAbsolutePath() + " result=" + mk);
            } else {
                XposedBridge.log("[DeviceReset] AUTO-GENERATE internal dir already exists: " + filesDir.getAbsolutePath());
            }

            // 用FileOutputStream写入内部目录（比Files.write更兼容）
            boolean internalOk = writeFile(sentinel, jsonStr);
            XposedBridge.log("[DeviceReset] AUTO-GENERATE internal write " + (internalOk ? "成功" : "失败")
                    + " path=" + sentinel.getAbsolutePath()
                    + " exists=" + sentinel.exists() + " size=" + (sentinel.exists() ? sentinel.length() : -1));

            // 同时写入外部存储备份（UI端可读）和运行时值
            if (externalDirPath != null) {
                try {
                    File extDir = new File(externalDirPath);
                    if (!extDir.exists()) {
                        boolean emk = extDir.mkdirs();
                        XposedBridge.log("[DeviceReset] AUTO-GENERATE mkdirs external=" + externalDirPath + " result=" + emk);
                    }
                } catch (Throwable e) {
                    XposedBridge.log("[DeviceReset] AUTO-GENERATE external mkdirs异常: " + e.getMessage());
                }
                boolean extOk = writeBackup(externalDirPath, jsonStr);
                File extSentinel = new File(externalDirPath, SENTINEL_FILE);
                XposedBridge.log("[DeviceReset] AUTO-GENERATE external backup write " + (extOk ? "成功" : "失败")
                        + " path=" + extSentinel.getAbsolutePath()
                        + " exists=" + extSentinel.exists() + " size=" + (extSentinel.exists() ? extSentinel.length() : -1));
                boolean rtOk = writeRuntime(externalDirPath, jsonStr);
                File rtFile = new File(externalDirPath, RUNTIME_FILE);
                XposedBridge.log("[DeviceReset] AUTO-GENERATE runtime write " + (rtOk ? "成功" : "失败")
                        + " path=" + rtFile.getAbsolutePath()
                        + " exists=" + rtFile.exists() + " size=" + (rtFile.exists() ? rtFile.length() : -1));
            } else {
                XposedBridge.log("[DeviceReset] AUTO-GENERATE externalDirPath为null，跳过外部备份和runtime写入");
            }

            // 尝试设置全局可读
            try {
                Process chmod = Runtime.getRuntime().exec(new String[]{"chmod", "666", sentinel.getAbsolutePath()});
                int code = chmod.waitFor();
                XposedBridge.log("[DeviceReset] AUTO-GENERATE chmod 666 exit=" + code + " path=" + sentinel.getAbsolutePath());
            } catch (Throwable e) {
                XposedBridge.log("[DeviceReset] AUTO-GENERATE chmod异常: " + e.getMessage());
            }

            cachedIdentity = newIdentity;
            checked = true;
            XposedBridge.log("[DeviceReset] ===AUTO-GENERATE DONE=== androidId=" + newIdentity.androidId
                    + " internalExists=" + sentinel.exists()
                    + (externalDirPath != null ? " externalExists=" + new File(externalDirPath, SENTINEL_FILE).exists() : ""));
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
            if (target.getParentFile() != null && !target.getParentFile().exists()) {
                boolean pm = target.getParentFile().mkdirs();
                XposedBridge.log("[DeviceReset] writeFile mkdirs parent=" + target.getParent() + " result=" + pm);
            }
            FileOutputStream fos = new FileOutputStream(target);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
            fos.close();
            boolean ok = target.exists() && target.length() > 0;
            XposedBridge.log("[DeviceReset] writeFile FileOutputStream " + (ok ? "成功" : "失败(空文件)")
                    + " path=" + target.getAbsolutePath() + " size=" + (target.exists() ? target.length() : -1));
            return ok;
        } catch (Throwable e) {
            XposedBridge.log("[DeviceReset] FileOutputStream写入失败 " + target.getAbsolutePath() + ": " + e.getMessage());
            // 兜底：用sh -c写入
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                        "cat > '" + target.getAbsolutePath() + "' << 'DRS_EOF'\n" + content + "\nDRS_EOF\n"});
                p.waitFor();
                boolean ok = target.exists() && target.length() > 0;
                XposedBridge.log("[DeviceReset] sh兜底写入 " + (ok ? "成功" : "失败") + " exit=" + p.exitValue()
                        + " path=" + target.getAbsolutePath() + " size=" + (target.exists() ? target.length() : -1));
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

    /** 写入运行时值文件（只由模块写，记录进程本次真正加载使用的身份，UI不写此文件） */
    private static boolean writeRuntime(String externalDirPath, String json) {
        try {
            File extDir = new File(externalDirPath);
            if (!extDir.exists()) extDir.mkdirs();
            File runtime = new File(extDir, RUNTIME_FILE);
            boolean ok = writeFile(runtime, json);
            XposedBridge.log("[DeviceReset] 运行时值写入 " + (ok ? "成功" : "失败") + " androidId=" );
            return ok;
        } catch (Throwable e) {
            XposedBridge.log("[DeviceReset] 运行时值写入异常: " + e.getMessage());
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
