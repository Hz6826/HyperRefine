# 状态栏图标预览资源来源

`app/src/main/res/drawable/ic_stat_sys_*.xml` 为 MIUI/HyperOS 状态栏图标的矢量重绘资源，取自
[XiaomiHelper / HyperHelper](https://github.com/HowieHChen/XiaomiHelper/app/src/main/res/drawable)，
用于设置页的状态栏图标预览，不参与 SystemUI 运行时绘制。

- 原项目许可证：GNU General Public License v3.0（GPL-3.0）。
- 本仓库整体以 AGPL-3.0 分发，与 GPL-3.0 兼容（AGPL-3.0 §13）；这些资源的原始版权与许可声明保留。
- 本地改动：仅调整资源文件名以匹配本模块组件 ID；未修改图形语义。新增图标时须同步本文件与应用内
  「关于 → 第三方许可证与致谢」中的 XiaomiHelper 条目。
- 预览中的电池与隐私指示已改为按状态自绘，因此不再引用 `handle_battery` 与 `privacy_notice`；两个资源保留以兼容旧构建，不参与当前预览。
- 涉及文件：`ic_stat_sys_airplane`、`alarm_clock`、`bluetooth`、`bluetooth_handsfree_battery`、
  `camera`、`car`、`compound`、`dist_compute`、`glasses`、`handle_battery`、`headset`、`hotspot`、
  `location`、`micphone`、`mobile`、`net_speed`、`nfc`、`no_sim`、`pad`、`pc`、`phone`、
  `privacy_notice`、`second_space`、`sound_box`、`sound_box_group`、`sound_box_screen`、`speech_hd`、
  `stealth`、`stereo`、`tv`、`unknown`、`volume`、`vpn`、`wifi`、`wireless_headset`、`zen`。
