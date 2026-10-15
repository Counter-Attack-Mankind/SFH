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

## v0.2 进展（通用武器属性修改系统）

`Stats_Guns.Init` 的真实 AVM2 字节码：方法体索引 11，共 6399 条指令，其中可达 4341 条；`MAX_STACK=45`、`INIT_SCOPE=3`、`MAX_SCOPE=4`、无异常表。可达指令中出现 **81** 次 `callpropvoid addGun`（多名字索引 2500，`operands[1]=33`）。

| 能力 | 状态 |
|---|---|
| 任意已注册武器的精确 ID 定位 | 已实现，原版上解析出 81/81 把武器、2673 个实参，解析器自检 `RESOLVER_SELF_TEST=OK` |
| damage / shootDelay / clipSize / recoil | 已实现；另支持 range、force、clipSpare、splash、splashMult、xOff、yOff |
| 大于 127 的数值 | 已实现；`pushbyte` / `pushshort` / `pushint` / `pushuint` / `pushdouble` 自动选择，已实测到 32767 与 1000000 |
| 单 JSON 多武器多属性 | 已实现；实测 2 把武器 6 项修改，含 0.25 → 0.1 的浮点改写 |
| 构建后静态复核全部目标属性 | 已实现（`verify-config`，逐项重新解析产物） |
| 原版隔离比对（只有目标实参变化） | 已实现，`ISOLATION_CHECK=OK` / `CHANGED_ARGUMENTS=6` |
| 原子性失败处理 | 已实现；失败时删除临时产物、正式产物与旧构建报告 |
| v0.1 回归 | 通过；`15 → 100` 与 `15 → 127` 均可用，产物 SHA-256 与既有版一致 |
| 构建文档与使用说明 | 见 `docs/weapon-pack-workflow.md` 与 `README.md` |

自动化测试：`scripts/test.ps1` 全部通过（v0.1 回归 + v0.2 批量共 88 项断言）。

v0.2 批量构建产物：`builds/SFH2_CustomWeapons.swf`，SHA-256 `7146AF66F60A1680F1B0E632F54E613952A0481C8A24F54B1718FE886788DC65`。

**未完成**：真实游戏运行验证。静态检查只证明字节码描述了所要求的数值，不证明游戏实际表现。

