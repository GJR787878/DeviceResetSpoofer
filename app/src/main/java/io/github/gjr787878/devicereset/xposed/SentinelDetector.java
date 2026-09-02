package io.github.gjr787878.devicereset.xposed;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 哨兵文件检测器 —— 整个模块的核心。
 *
 * 原理：
 * - 在目标APP的私有目录写入一个隐藏标记文件 .identity_sentinel，内容为当前身份的JSON。
 * - 每次目标APP冷启动时，在最早的hook点检查这个文件是否存在。
 * - 文件不存在 → 用户刚清除了应用数据（清数据会删除整个私有目录）→ 生成全新身份。
 * - 文件存在 → 读取并沿用旧身份。
 *
 * 为什么可靠：清除应用数据的本质是 rm -rf /data/data/<package>/，
 * 哨兵文件在这个目录内，必然被一起删除，不需要任何系统广播。
 */
public class SentinelDetector {
    private static final String SENTINEL_FILE = ".identity_sentinel";
    private static Identity cachedIdentity = null;
    private static boolean checked = false;

    /**
     * 检查哨兵文件，返回本次启动应使用的身份（需要Context）。
     */
    public static Identity checkAndGetIdentity(Context context) {
        return checkAndGetIdentityByDir(context.getFilesDir().getAbsolutePath());
    }

    /**
     * 直接用filesDir路径检测哨兵（不需要Context，用于更早的Hook点）。
     */
    public static Identity checkAndGetIdentityByDir(String filesDirPath) {
        if (checked && cachedIdentity != null) {
            return cachedIdentity;
        }
        try {
            File filesDir = new File(filesDirPath);
            File sentinel = new File(filesDir, SENTINEL_FILE);
            if (sentinel.exists()) {
                byte[] data = Files.readAllBytes(sentinel.toPath());
                String json = new String(data, StandardCharsets.UTF_8);
                Identity id = Identity.fromJson(json);
                if (id != null && id.androidId != null) {
                    cachedIdentity = id;
                    checked = true;
                    return id;
                }
            }
            // 哨兵不存在或读取失败 → 生成全新身份
            Identity newIdentity = IdentityGenerator.generateRandom();
            if (!filesDir.exists()) {
                filesDir.mkdirs();
            }
            Files.write(sentinel.toPath(), newIdentity.toJson().getBytes(StandardCharsets.UTF_8));
            cachedIdentity = newIdentity;
            checked = true;
            return newIdentity;
        } catch (Throwable t) {
            if (cachedIdentity == null) {
                cachedIdentity = IdentityGenerator.generateRandom();
            }
            checked = true;
            return cachedIdentity;
        }
    }

    /**
     * 手动重置身份（配置界面的"手动重置"按钮调用）。
     */
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
