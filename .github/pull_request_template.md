## 变更说明

<!-- 说明这个 PR 做了什么、为什么需要它。 -->

## 变更类型

- [ ] 新功能
- [ ] 问题修复
- [ ] 重构 / 性能优化
- [ ] 文档
- [ ] 构建 / CI

## 影响范围

- 目标进程 / 作用域：
- 功能入口（如：系统界面 → 状态栏 → 状态栏图标）：
- 配置键 / 公开接口变化：

## 验证

- [ ] `.\gradlew.bat :hook:compileDebugKotlin :app:assembleDebug --console=plain` 构建通过
- [ ] 已在 LSPosed 中启用并重启目标进程验证；未验证时请在下方说明原因
- [ ] 涉及系统框架 / `system_server` 时已说明是否重启设备

验证环境：

```text
HyperOS / Android：
机型：
模块版本：
```

## 检查清单

- [ ] 配置键两端一致：`OptionSpec.key` 与 `BaseHook.key` 相同，`HookPrefs.GROUP` 与 `PrefsStore.REMOTE_GROUP` 同步
- [ ] 新增作用域已同步 `hook/src/main/resources/META-INF/xposed/scope.list` 与对应 `BaseLoad.targetPackages`
- [ ] 依赖 / 设备不满足时使用「禁用灰显」而非隐藏，动画使用 `MiuixExpandSpec`
- [ ] 已按需同步 `README.md`、`changelog.md`、`docs/`（通用文档在根目录，功能说明在 `docs/features/`）
- [ ] 未提交 `reverse_report/`、`release.keystore`、日志、账号或其他隐私 / 密钥数据
- [ ] 复用第三方代码或资源时已核对许可证，并同步应用内「第三方许可证与致谢」

## 关联 Issue

<!-- 如：Closes #123；无则写「无」。 -->

## 截图 / 日志（可选）

<!-- 涉及 UI 时建议附前后对比；日志请先移除隐私信息。 -->
