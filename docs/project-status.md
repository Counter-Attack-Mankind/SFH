# 工程现状报告

审计日期：2026-10-15。工作区：`E:\SFH2-Mod`。

## 基线与工具

| 项目 | 结果 |
|---|---|
| 原版 SWF | `original/SFH_CN_Fixed.swf`，26,221,972 bytes，SHA-256 `B4FFEAD005A6624A8A0942FA6AAF10AB114F1C4777EE6F2F5DE8C6E5A602C0DE` |
| 既有人工测试版 | `builds/SFH2_USP_Test.swf`，SHA-256 `A4ECA56C5FF91E6260318A72DEE95EAAB7E69312F9E5ADDC158CCF16B3E065BA` |
| FFDec | `tools/FFDec`，本地 CLI 报告 26.3.0 nightly build 3590 |
| Java | `tools/Java`，OpenJDK 17.0.20.1 |
| Ruffle | `tools/Ruffle/ruffle.exe` 存在；本次未把启动结果算作运行验证 |
| Git | 仓库无提交，审计开始时 `builds/`、`decompiled/`、`original/`、`tools/` 均未跟踪 |

原版与人工测试版大小相同但哈希不同。自动生成版的哈希与人工测试版相同，这是强静态证据；仍不能代替人工运行验证。

## 反编译内容

共 20,911 个文件、491 个 `.as` 文件。分类计数：

| 分类 | 文件数 |
|---|---:|
| buttons | 24 |
| fonts | 24 |
| frames | 25 |
| images | 37 |
| morphshapes | 84 |
| scripts | 491 |
| shapes | 1,014 |
| sounds | 174 |
| sprites | 18,618 |
| symbolClass | 1 |
| texts | 419 |
| assets / movies | 0 / 0 |

以下核心类均在 `decompiled/scripts` 中恰好存在一份：`Main`、`Game`、`Player`、`AI`、`Unit`、`UnitMC`、`Menu`、`Hud`、`Stats_Guns`、`Stats_Classes`、`Stats_Skills`、`Stats_Streaks`、`MatchSettings`、`Guns`、`Movement`、`Status`。

## 可靠性边界

反编译结果含 `§§push`、`§§pop`、不可达分支和不自然控制流。本文档仅把清晰的数据流和经 P-code/工具复核的内容列为确定结论；不把这些 `.as` 文件视作可重新编译源码。`decompiled` 只作为只读证据库。
