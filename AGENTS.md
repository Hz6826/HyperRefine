# HyperRefine 模块开发

HyperRefine 是基于 MiuixGuiTemplate 的 LSPosed 模块（libxposed API 102 + Miuix Compose，AGPL-3.0），面向 HyperOS 4，minSdk 35，仅构建 arm64-v8a。

- `:app` — Compose UI、配置系统（`app/src/main/java/cn/ianzb/hyperrefine/prefs/`）、服务与状态（`.../xposed/`）、二级页模板
- `:hook` — libxposed 入口 `XposedEntry`、JavaHook 封装（`HookHelper` / `BaseHook` / `BaseLoad`）、NativeHook 封装、DexKit 缓存、Rust 原生库（`hook/src/main/rust/nativehook/`）
- Hook 功能按领域分包：`systemui/`、`connect/`、`browser/`、`passkey/`、`miuix/`、`weather/` 等；配置键常量放在各包的 `*Keys.kt` 或 companion 中，App 与 hook 两侧共用同一字符串。

## 文档地图（动手前先读对应文档）

项目自带文档是权威来源，优先于本文的摘要：

| 任务 | 必读 |
|---|---|
| 新增 / 修改 Hook、DexKit、配置项、UI 组件、接入步骤 | `docs/API.md`（§1 Hook 封装、§2 DexKit、§3 配置、§5 UI、§6 二级页、§7 接入） |
| 包名 / 图标 / 链接 / 模块元数据等二次开发全量清单 | `docs/CUSTOMIZE.md` |
| 真机 adb 授权、逆向产物存放、第三方复用合规 | `docs/WORKFLOW.md`（红线，必读） |
| Rust 原生 Hook | `docs/NATIVE_HOOK.md` |
| 发布 / CI / 签名 | `README.md`「发布与 CI」章节 |
| 版本变更记录 | `changelog.md` |

## 工程不变量（违反会被评审视为 bug）

1. **配置键两端一致**：`OptionSpec.key` 必须等于对应 `BaseHook.key`（hook 侧用 `HookPrefs.getBoolean(key, false)` 读取）；`HookPrefs.GROUP == PrefsStore.REMOTE_GROUP == "hyperrefine_remote"`，两处常量必须同步；`prefs_key_` 前缀由代码自动处理，声明 key 时不要手写前缀。
2. **作用域**：新增目标包必须同时写入 `hook/src/main/resources/META-INF/xposed/scope.list` 并出现在对应 `BaseLoad.targetPackages`；原生库文件名写入 `native_init.list`。
3. **可见性**：依赖 / 设备不满足时一律「禁用灰显不隐藏」——用 `OptionSpec.dependsOn`（+ `dependsOnValue`）、`rememberOptionEnabled(spec)`、`deviceScope` 实现，禁止用 `AnimatedVisibility` 或条件渲染按配置值隐藏组件 / 分区。
4. **标题文案**：功能页分区标题只传 `titleRes`（单语言）；作为二级入口的卡片用总结性名词短语做 `titleRes`、尽量不写 `summaryRes`，描述性内容放二级页内部。
5. **动画**：组件出现 / 隐藏统一用 `MiuixExpandSpec`（`expandVertically` / `shrinkVertically(animationSpec = MiuixExpandSpec)`），禁止默认参数或自定时长。
6. **滑块**：标题要与数值同行必须在 `OptionSpec` 设 `sliderValueLabelRes`（否则标题会落到滑动组件外面）；`masterKey` 只控制启用 / 禁用，不控制显隐。
7. **二级页面**：继承 `BaseSubPageActivity` + 在 `AndroidManifest.xml` 注册 + 父页 `HookSubPage(..., subPages = ...)` 登记（子页自身不放搜索栏；多级嵌套逐层登记，搜索可直达）。
8. **Hook 生效状态**：`OptionSpec.showStatus = true` 后由目标进程回报（`HookStatusReporter` → `HookStatusStore`）；开关变更后必须重启目标进程才会刷新为「已生效」。
9. **重启应用**：顶栏统一 `QuickActionsAction` / `QuickActionDialog`；`com.android.systemui` 走 `AppRestarter.restartSystemUi()`（不重启系统），`system` / `android` / `system_server` 二次确认后 `reboot`。

## 常规任务路径

- **新增 JavaHook**：`scope.list` → `BaseHook` 子类（`init()`，必要时 `useDexKit()` + `requiredMember`）→ `BaseLoad.onPackageLoaded` 中 `initHook(hook, HookPrefs.getBoolean(key, false))` → `HookEntryRegistry` 登记 → 声明 `OptionSpec` 并接入功能页（`ui/screen/features/FeaturesPage.kt` 的 `featureSpecs()`）。完整示例见 `docs/API.md` §7。
- **新增 NativeHook（Rust）**：`native_init`（`#[no_mangle] pub extern "C"`，返回加载回调）→ `cargo ndk -t arm64-v8a -o hook/src/main/jniLibs build --release` → `.so` 名写入 `native_init.list` → `BaseNativeHook` + `initNativeHook(...)`；hook 必须幂等。详见 `docs/NATIVE_HOOK.md`。
- **新增配置项**：`OptionSpec` → `OptionRegistry.register` → 页面 `HookOptionsPage(sections = ...)` 渲染；组件行为与接线规范见 `docs/API.md` §5 / §6。
- **新增依赖 / 参考项目**：`ui/screen/about/LicensePage.kt` 的 `licenseSections` + `strings.xml` 的 `licenses_section_*`；按需同步 `README.md` / `docs/CUSTOMIZE.md` / `changelog.md`。

## 构建与验证

- 构建：`.\gradlew.bat :hook:compileDebugKotlin :app:assembleDebug --console=plain`（JDK 25；`local.properties` 指向本机 Android SDK）。产物：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。
- 长驻命令（Gradle / `adb logcat`）**禁止**用 `| Select-Object -Last N` 截断输出（会缓冲整个输出流、看似卡死）；需要落盘时重定向到 `$env:TEMP` 再读尾部（见 `docs/WORKFLOW.md` §4）。
- Release：`release.keystore` + `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 环境变量；`versionCode` 自增 + `versionName`，Release Notes 取自 `changelog.md` 对应版本段落。
- 真机验证：LSPosed 启用模块 → 打开开关 → 重启目标应用 → 卡片显示「已生效」；必要时 `adb logcat -s <TAG>` 排查。

## 合规红线（docs/WORKFLOW.md）

- **改机需授权**：任何 `adb` 操作逐次取得用户明确同意、且在其白名单内；默认只读，禁止修改设备 / 抓取隐私 / 提权。
- **逆向产物**：只放 `reverse_report/<包名>/`（本地 git 忽略，永不提交、不 `git add -f`），只保留定位信息（包名 / 类名 / 方法签名 / 字段）。
- **第三方复用**：先查许可证——Apache-2.0 / MIT / BSD / LGPL-3.0 / GPL-3.0 / AGPL-3.0 可复用（保留原始声明并标注改动）；无许可证 / 非开源只借鉴思路并在 `LicensePage.kt` 致谢，不得复制代码 / 资源 / 文案。
- **收尾同步**：涉及许可证或致谢变更时同步 `LicensePage.kt`、`README.md`、`docs/CUSTOMIZE.md`、`changelog.md`。

## 快速自检

- [ ] `OptionSpec.key` 与 `BaseHook.key` 一致；`HookPrefs.GROUP` 与 `PrefsStore.REMOTE_GROUP` 一致
- [ ] `scope.list` 覆盖全部 `BaseLoad.targetPackages`
- [ ] 依赖 / 设备不满足时灰显而非隐藏；显隐动画使用 `MiuixExpandSpec`
- [ ] `.\gradlew.bat :hook:compileDebugKotlin :app:assembleDebug --console=plain` 构建通过
- [ ] 真机验证 Hook 生效；`changelog.md` / 许可页按需更新
