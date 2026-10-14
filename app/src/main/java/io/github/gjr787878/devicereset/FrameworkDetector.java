package io.github.gjr787878.devicereset;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 框架精确检测器：自动识别用户手机里安装的是 <b>官方 LSPosed</b> 还是 <b>JingMatrix/Vector</b>，
 * 读取版本号，并定位可用的 CLI（{@code lspctl} / {@code cli}）。
 *
 * <p>两个分支的目录、进程名、数据库位置完全相同（{@code /data/adb/lspd}、{@code pidof lspd}、
 * {@code modules_config.db}），真正区别只有：</p>
 * <ul>
 *   <li>Magisk 模块 id：{@code zygisk_lsposed}（官方） vs {@code zygisk_vector}（Vector）；</li>
 *   <li>管理器包名：{@code org.lsposed.manager} vs {@code org.matrix.vector.manager}；</li>
 *   <li>CLI 名：{@code lspctl} vs {@code cli}；</li>
 *   <li>CLI 写作用域：官方需开发者模式 + ADB 来源门（App 无人值守不可用），
 *       Vector 为编译期令牌（App 直接可用）。</li>
 * </ul>
 *
 * <p>检测综合三路证据并交叉校验：① 已安装管理器包名；② 活动 Magisk 模块的 module.prop
 * （name/version/versionCode）；③ CLI 脚本是否存在。结果在 {@link Info} 中返回。</p>
 */
public final class FrameworkDetector {

    private static final String TAG = "FrameworkDetector";

    public static final int FLAVOR_NONE = 0;
    /** 官方 LSPosed（org.lsposed / lspctl）。 */
    public static final int FLAVOR_OFFICIAL = 1;
    /** JingMatrix/Vector（org.matrix.vector / cli）。 */
    public static final int FLAVOR_VECTOR = 2;

    public static final class Info {
        public final int flavor;
        public final String id;          // zygisk_lsposed / zygisk_vector
        public final String name;        // LSPosed / Vector
        public final String version;     // v2.2.1 (7912)
        public final int versionCode;
        public final String moduleDir;   // /data/adb/modules/<id>/
        public final String cli;         // CLI 绝对路径（可能为 null）
        public final String cliName;     // lspctl / cli
        public final boolean managerInstalled;

        Info(int flavor, String id, String name, String version, int versionCode,
             String moduleDir, String cli, String cliName, boolean managerInstalled) {
            this.flavor = flavor;
            this.id = id;
            this.name = name;
            this.version = version;
            this.versionCode = versionCode;
            this.moduleDir = moduleDir;
            this.cli = cli;
            this.cliName = cliName;
            this.managerInstalled = managerInstalled;
        }

        public boolean isVector() { return flavor == FLAVOR_VECTOR; }
        public boolean isOfficial() { return flavor == FLAVOR_OFFICIAL; }

        /** 该分支的作用域写操作是否可由 App 经 root 无人值守直接完成（Vector 可以，官方不行）。 */
        public boolean cliWritesUsable() { return flavor == FLAVOR_VECTOR && cli != null; }

        public String displayName() {
            if (flavor == FLAVOR_NONE) return "未检测到";
            return name + (version != null && !version.isEmpty() ? " " + version : "");
        }
    }

    private static final Info NONE =
            new Info(FLAVOR_NONE, "", "未检测到", "", -1, "", null, "", false);

    /** 检测当前活动框架（结果不缓存，调用方需要时自行缓存）。 */
    public static Info detect() {
        String script =
                "for id in zygisk_lsposed zygisk_vector; do\n" +
                "  for base in /data/adb/modules_update /data/adb/modules; do\n" +
                "    d=\"$base/$id\"\n" +
                "    if [ -f \"$d/module.prop\" ]; then\n" +
                "      disabled=0\n" +
                "      [ -f \"$d/disable\" ] || [ -f \"$d/remove\" ] && disabled=1\n" +
                "      nm=$(sed -n 's/^name=//p' \"$d/module.prop\")\n" +
                "      ver=$(sed -n 's/^version=//p' \"$d/module.prop\")\n" +
                "      vc=$(sed -n 's/^versionCode=//p' \"$d/module.prop\")\n" +
                "      cli=\"\"\n" +
                "      [ -x \"$d/cli\" ] && cli=\"$d/cli\"\n" +
                "      [ -z \"$cli\" ] && [ -x \"$d/lspctl\" ] && cli=\"$d/lspctl\"\n" +
                "      [ -z \"$cli\" ] && [ -x /data/adb/lspd/cli ] && cli=/data/adb/lspd/cli\n" +
                "      cln=\"\"\n" +
                "      case \"$cli\" in *lspctl) cln=lspctl;; *cli) cln=cli;; esac\n" +
                "      echo \"BLOCK id=$id disabled=$disabled name=$nm ver=$ver vc=$vc dir=$d/ cli=$cli cln=$cln\"\n" +
                "    fi\n" +
                "  done\n" +
                "done\n" +
                "echo 'PKG '$(pm list packages 2>/dev/null | grep -E 'org.lsposed.manager|org.matrix.vector.manager' | sed 's/package://' | tr '\\n' ' ')\n";
        RootScopeManager.Result r = RootScopeManager.runRoot(script);
        Log.i(TAG, "detect: exit=" + r.exitCode + "\n" + r.output);
        if (!r.ok() || r.output.isEmpty()) return NONE;

        boolean hasOfficialManager = false, hasVectorManager = false;
        List<Info> candidates = new ArrayList<>();
        for (String line : r.output.split("\n")) {
            line = line.trim();
            if (line.startsWith("BLOCK ")) {
                java.util.Map<String, String> kv = parseKv(line.substring(6));
                String id = kv.get("id");
                int flavor = "zygisk_vector".equals(id) ? FLAVOR_VECTOR
                        : "zygisk_lsposed".equals(id) ? FLAVOR_OFFICIAL : FLAVOR_NONE;
                int vc = -1;
                try { vc = Integer.parseInt(kv.get("vc")); } catch (Throwable ignored) {}
                boolean disabled = "1".equals(kv.get("disabled"));
                Info info = new Info(flavor, nz(id), nz(kv.get("name")), nz(kv.get("ver")),
                        vc, nz(kv.get("dir")), nz(kv.get("cli")), nz(kv.get("cln")), false);
                if (!disabled) candidates.add(info);
            } else if (line.startsWith("PKG ")) {
                String pkgs = line.substring(4);
                if (pkgs.contains("org.lsposed.manager")) hasOfficialManager = true;
                if (pkgs.contains("org.matrix.vector.manager")) hasVectorManager = true;
            }
        }
        if (candidates.isEmpty()) return NONE;

        // 优先用管理器包名确定活动分支；否则取第一个活动模块。
        Info picked = null;
        for (Info info : candidates) {
            if (info.flavor == FLAVOR_VECTOR && hasVectorManager) { picked = info; break; }
            if (info.flavor == FLAVOR_OFFICIAL && hasOfficialManager) { picked = info; break; }
        }
        if (picked == null) picked = candidates.get(0);

        boolean manager = picked.flavor == FLAVOR_VECTOR ? hasVectorManager : hasOfficialManager;
        return new Info(picked.flavor, picked.id, picked.name, picked.version, picked.versionCode,
                picked.moduleDir, picked.cli, picked.cliName, manager);
    }

    private static java.util.Map<String, String> parseKv(String s) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        for (String pair : s.split(" ")) {
            int eq = pair.indexOf('=');
            if (eq > 0) m.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return m;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
