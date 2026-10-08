package io.github.gjr787878.devicereset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * LSPosed / Vector 官方 libxposed:service 动态作用域网关（免开框架管理器）。
 *
 * 机制（参考官方框架源码 ModuleAppService / NotificationManager）：
 * - 模块 UI 进程启动且模块已启用时，框架通过 Manifest 声明的 XposedProvider
 *   （authority = &lt;package&gt;.XposedService）把框架 binder 注入本进程；
 * - 注册监听后即可在应用内：
 *   - requestScope(pkgs, cb)：框架下发一条高优先级通知，通知上直接有
 *     「批准 / 拒绝 / 不再询问」按钮，无需打开框架管理器；用户点「批准」后
 *     目标应用进入模块作用域，框架随后把模块注入该应用进程；
 *   - removeScope(pkgs)：立即移除作用域（无弹窗）；
 *   - getScope()：读取当前作用域（权威结果）。
 * - 无需 root、无需直接写 modules_config.db、无需重启手机。
 *
 * 安全边界（官方设计，无法绕过）：模块不能静默把自己加入某个应用的作用域，
 * 必须由用户在通知上点一次「批准」。
 *
 * 兼容性：不支持 libxposed service 的旧框架下 isConnected()=false，
 * 调用方应提示用户在框架管理器启用模块（root 只读库仅用于展示，不再写库）。
 */
public final class LSPosedScopeHelper {
    private static volatile XposedService sService;
    private static boolean sRegistered;

    /** 框架连接状态变化回调（binder 到达 / 死亡时触发，用于立刻刷新 UI） */
    public interface ConnectionCallback {
        void onConnected(boolean connected);
    }

    private static final List<ConnectionCallback> sCallbacks = new ArrayList<>();

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
                notifyCallbacks(true);
            }

            @Override
            public void onServiceDied(XposedService service) {
                if (sService == service) {
                    sService = null;
                    notifyCallbacks(false);
                }
            }
        });
    }

    /** 订阅连接状态变化（可在 Activity 中注册后触发刷新） */
    public static void addConnectionCallback(ConnectionCallback callback) {
        if (callback == null) return;
        synchronized (sCallbacks) {
            sCallbacks.add(callback);
        }
    }

    private static void notifyCallbacks(boolean connected) {
        List<ConnectionCallback> snapshot;
        synchronized (sCallbacks) {
            snapshot = new ArrayList<>(sCallbacks);
        }
        for (ConnectionCallback cb : snapshot) {
            try {
                cb.onConnected(connected);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 是否已连接框架（支持 libxposed service 的新版框架才为 true）。 */
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

    /** 请求把单个包加入作用域：框架下发带「批准/拒绝」按钮的通知，结果通过 listener 回调。 */
    public static void requestScope(String pkg, XposedService.OnScopeEventListener listener) {
        if (pkg == null) return;
        requestScope(Collections.singletonList(pkg), listener);
    }

    /** 批量请求把多个包加入作用域（一次通知、一次批准覆盖全部）。 */
    public static void requestScope(List<String> packages, XposedService.OnScopeEventListener listener) {
        XposedService s = sService;
        if (s == null || packages == null || packages.isEmpty()) return;
        try {
            s.requestScope(new ArrayList<>(packages),
                    listener != null ? listener : new XposedService.OnScopeEventListener() {
                    });
        } catch (Throwable ignored) {
        }
    }

    /** 移除单个包的作用域（立即生效，无弹窗）。 */
    public static void removeScope(String pkg) {
        if (pkg == null) return;
        removeScope(Collections.singletonList(pkg));
    }

    /** 批量移除作用域。 */
    public static void removeScope(List<String> packages) {
        XposedService s = sService;
        if (s == null || packages == null || packages.isEmpty()) return;
        try {
            s.removeScope(new ArrayList<>(packages));
        } catch (Throwable ignored) {
        }
    }
}
