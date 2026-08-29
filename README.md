# DeviceResetSpoofer

一个 LSPosed 模块：对选中的应用，在**清除应用数据后自动生成全新的设备识别码**。

## 功能

- 清除目标应用数据后，下次冷启动自动获得全新设备身份
- 支持伪装：Android ID、广告ID(AAID)、IMEI/MEID、设备型号(Build)、MAC地址、GSF ID、运营商信息
- 按应用隔离，每个应用独立身份
- 手动重置某个应用的身份
- 应用搜索、系统应用过滤

## 原理（哨兵文件检测法）

Android 系统没有"应用数据被清除"的广播，但清除数据的本质是删除 `/data/data/<package>/` 整个目录。

模块在目标应用私有目录放置一个隐藏哨兵文件 `.identity_sentinel`，内容为当前身份的 JSON。

每次应用冷启动时：
- 哨兵文件存在 → 数据没被清 → 沿用旧身份
- 哨兵文件不存在 → 数据被清了 → 生成全新身份并写入新哨兵文件

## 使用方法

1. 手机已 root，安装 Magisk + LSPosed（或 Zygisk + LSPosed）
2. 安装本模块 APK
3. 在 LSPosed 管理器中启用模块，并在**作用域**中勾选目标应用
4. 打开模块配置界面，勾选需要保护的应用
5. 在系统设置中清除该应用数据
6. 重新打开应用，它将获得全新的设备识别码

## 构建

### GitHub Actions（推荐）
Push 代码到 main 分支，Actions 会自动编译，在 Artifacts 中下载 APK。

### 本地构建
```bash
./gradlew assembleDebug
# APK 输出在 app/build/outputs/apk/debug/
```

## 项目结构

```
app/src/main/java/com/devicereset/
├── xposed/
│   ├── MainHook.java           # LSPosed 入口
│   ├── SentinelDetector.java   # 哨兵文件检测器（核心）
│   ├── IdentityGenerator.java  # 随机身份生成器
│   └── Identity.java           # 身份数据模型
├── hooks/
│   ├── AndroidIdHook.java      # Android ID
│   ├── AdvertisingIdHook.java  # 广告ID / AppSet ID
│   ├── TelephonyHook.java      # IMEI / MEID / 运营商
│   ├── BuildInfoHook.java      # 设备型号信息
│   ├── WifiMacHook.java        # MAC 地址
│   └── GsfIdHook.java          # GSF ID
├── ui/
│   ├── MainActivity.java       # 配置界面
│   ├── AppListAdapter.java     # 应用列表
│   └── AppInfo.java            # 应用信息
└── Config.java                  # 配置管理
```

## 注意事项

- 本模块仅用于个人技术测试和隐私保护
- 部分应用可能检测 Xposed/Root 痕迹，存在账号封禁风险
- 加壳应用可能需要额外处理（建议在 LSPosed 中勾选"排除资源钩子"等选项）
- native 层直接读取系统属性的应用，Java 层 Hook 无法拦截

## License

MIT
