package io.github.gjr787878.devicereset;

import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * LSPosed 官方 libxposed:service 动态作用域助手（§8.2 免开 LSPosed 管理器）。
 *
 * 机制：模块 UI 进程启动时，LSPosed 框架通过 manifest 声明的 XposedProvider
 * （authority = <package>.XposedService）把框架 binder 注入本进程；
 * 注册 listener 后即可在应用内：
 *   - requestScope(pkg, cb)：弹出 LSPosed 授权窗，用户确认后目标应用进入模块作用域
 *   - removeScope(pkg)：移除指定应用的作用域
 *   - getScope()：读取当前作用域
 * 无需 root、无需写 modules_config.db、无需重启手机。
 *
 * 兼容性：老版本 LSPosed（无 libxposed service 支持）下 isConnected()=false，
 * 调用方应回退到 root 写库同步（autoSyncScopeToLSPosed）。
 */
public final class LSPosedScopeHelper {
    private static volatile XposedService sService;
    private static boolean sRegistered;

    private LSPosedScopeHelper() {
    }

    /** 注册框架 binder 监听；整个进程只注册一次（在 Activity onCreate 调用即可）。 */
    public static synchronized void init() {
        if (sRegistered) return;
        sRegistered = true;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                sService = service;
            }

            @Override
            public void onServiceDied(XposedService service) {
                if (sService == service) sService = null;
            }
        });
    }

    /** 是否已连接 LSPosed 框架（新版支持 libxposed service 的框架才为 true）。 */
    public static boolean isConnected() {
        return sService != null;
    }

    /** 当前模块作用域（包名列表）；未连接框架返回 null。 */
    public static List<String> getScope() {
        XposedService s = sService;
        if (s == null) return null;
        try {
            return s.getScope();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 请求把 pkg 加入作用域：LSPosed 弹出授权窗，结果通过 listener 回调。 */
    public static void requestScope(String pkg, XposedService.OnScopeEventListener listener) {
        XposedService s = sService;
        if (s == null) return;
        try {
            s.requestScope(java.util.Collections.singletonList(pkg),
                    listener != null ? listener : new XposedService.OnScopeEventListener() {
                    });
        } catch (Throwable ignored) {
        }
    }

    /** 移除 pkg 的作用域。 */
    public static void removeScope(String pkg) {
        XposedService s = sService;
        if (s == null) return;
        try {
            s.removeScope(java.util.Collections.singletonList(pkg));
        } catch (Throwable ignored) {
        }
    }
}
