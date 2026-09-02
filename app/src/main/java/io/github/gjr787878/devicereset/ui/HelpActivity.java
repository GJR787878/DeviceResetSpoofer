package io.github.gjr787878.devicereset.ui;

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import io.github.gjr787878.devicereset.R;

/**
 * 使用说明页面。
 */
public class HelpActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("使用说明");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        TextView helpText = findViewById(R.id.help_text);
        helpText.setMovementMethod(LinkMovementMethod.getInstance());
        helpText.setText(getHelpContent());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private CharSequence getHelpContent() {
        StringBuilder sb = new StringBuilder();

        sb.append("【核心功能】\n");
        sb.append("对选中的应用，在清除应用数据后自动生成全新的设备识别码。\n\n");

        sb.append("【工作原理】\n");
        sb.append("模块在目标应用私有目录放置一个隐藏哨兵文件 .identity_sentinel。\n");
        sb.append("清除应用数据会删除整个私有目录，哨兵文件也被删除。\n");
        sb.append("下次应用启动时检测到哨兵不存在，即生成全新设备身份并写入新哨兵。\n\n");

        sb.append("【使用步骤】\n");
        sb.append("1. 安装本模块APK\n");
        sb.append("2. 打开LSPosed管理器 → 模块 → 启用 DeviceResetSpoofer\n");
        sb.append("3. 点击模块 → 作用域 → 勾选你要保护的应用\n");
        sb.append("4. 重启手机（必须重启，LSPosed注入需要重启）\n");
        sb.append("5. 打开本模块配置界面 → 勾选目标应用\n");
        sb.append("6. 去系统设置 → 应用 → 目标应用 → 存储 → 清除数据\n");
        sb.append("7. 重新打开目标应用，它将获得全新的设备识别码\n\n");

        sb.append("【伪装的识别码】\n");
        sb.append("• Android ID (SSAID)\n");
        sb.append("• 广告ID (AAID) / AppSet ID\n");
        sb.append("• IMEI / MEID / IMSI / ICCID\n");
        sb.append("• Serial序列号 / MAC地址\n");
        sb.append("• GSF ID\n");
        sb.append("• 设备型号：品牌、型号、厂商、设备名、Build指纹\n");
        sb.append("• 运营商信息：运营商代码、名称、国家\n\n");

        sb.append("【常见问题】\n\n");

        sb.append("Q: 清除数据后身份没变？\n");
        sb.append("A: 检查以下几点：\n");
        sb.append("   1. LSPosed作用域是否勾选了目标应用\n");
        sb.append("   2. 模块配置界面是否勾选了目标应用\n");
        sb.append("   3. 是否重启了手机\n");
        sb.append("   4. 清除数据后是否完全杀掉进程再重新打开\n\n");

        sb.append("Q: 从LSPosed右下角启动按钮打开应用闪退？\n");
        sb.append("A: 这是LSPosed的快速启动功能与Hook注入时序冲突导致的。\n");
        sb.append("   解决方法：不要用LSPosed的启动按钮，直接从桌面图标打开应用。\n");
        sb.append("   如果首次打开闪退，先清除一次应用数据再打开即可。\n\n");

        sb.append("Q: 加壳应用不生效？\n");
        sb.append("A: 在LSPosed作用域设置中，对该应用勾选「排除资源钩子」选项。\n");
        sb.append("   部分加固应用可能需要额外处理。\n\n");

        sb.append("Q: 如何手动重置身份（不清除数据）？\n");
        sb.append("A: 在模块配置界面，点击右上角菜单 → 手动重置身份 → 选择应用。\n");
        sb.append("   重置后该应用下次启动将获得全新身份。\n\n");

        sb.append("Q: 每次启动都变身份？\n");
        sb.append("A: 正常情况下，只有清除数据后才会换身份。\n");
        sb.append("   如果每次启动都变，说明哨兵文件写入失败（可能是权限问题），\n");
        sb.append("   检查应用是否有存储权限，或尝试重新安装模块。\n\n");

        sb.append("【注意事项】\n");
        sb.append("• 本模块仅用于个人隐私保护和技术测试\n");
        sb.append("• 部分应用会检测Xposed/Root痕迹，存在账号封禁风险\n");
        sb.append("• native层直接读取系统属性的应用，Java层Hook无法拦截\n");
        sb.append("• 建议先在不重要的应用上测试，确认无误后再用于重要应用\n\n");

        sb.append("【查看日志排查】\n");
        sb.append("打开LSPosed管理器 → 日志 → 搜索「DeviceReset」\n");
        sb.append("可以看到模块的加载状态和身份生成信息。\n");

        return sb.toString();
    }
}
