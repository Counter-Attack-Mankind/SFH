# 角色、职业、外观与能力系统

## 职业属性与生命

`Stats_Classes.getClass`（`Stats_Classes.as:73-163`）定义 medic、sniper、soldier、tank 的 `hp/crit/aim/amm` 最小值和最大值，并按等级 1-50 线性插值。`Unit.setClass`（`Unit.as:250-442`）把这些值写入单位：`crit`、`aim`、`amm` 除以 100，`headBonus=1.45`、`critBonus=1.35`，再应用额外 HP 和技能修正。

`Status.reset`（`Status.as:101-127`）令 `hpMax` 和 `hpCur` 等于最终 `unitInfo.hp`。`Status.damage` 是唯一集中的伤害倍率/扣血入口；`Status.heal` 负责上限钳制。职业基础值、技能和状态效果因此是分层关系，而不是一个静态 HP 常量。

## 移动

`Movement.as:62-103` 给出基础常量：`xAcc=1.8`、`xMax=9.5`、蹲伏上限 4、重力 0.8、下落上限 20、跳跃 13。`resetMods`（`Movement.as:170-180`）把速度、上限、制动、跳跃、重力等倍率重置为 1；`EnterFrame` 从 `Movement.as:259` 根据按键、跳跃、蹲伏、降落伞和这些倍率计算实际运动。职业 `runType` 主要参与动画选择；已读代码没有显示职业直接改写 `xMax`。

## 技能与连杀

`Stats_Skills.Init`（`Stats_Skills.as:90-174`）注册技能元数据；`addSkill`（`Stats_Skills.as:176-202`）写入 ID、数值、解锁等级和说明。效果并不由注册表自动解释，而是散布在消费者中：

- `Unit.setClass`：`health/ammo/critical/combat/vital/adren` 修改最终单位属性（`Unit.as:304-360`）。
- `Guns`：`clip` 改变机枪弹药和扣弹（`Guns.as:110-137, 503-610`）。
- `Status.damage`：`will/shadow/resist/iron/operation/blur/bomb`（`Status.as:208-400`）。
- `Status.EnterFrame`：隐身、恢复及持续状态（从 `Status.as:460` 开始）。

`Stats_Streaks.Init`（`Stats_Streaks.as:98-194`）注册连杀需求与全局标志。`Unit.useKillstreak`（`Unit.as:1352-1609`）执行 `mine/smoke/surge/mirror/fire/vest/rapid`；标记为全局的效果转给 `Game.createKillstreak`（`Game.as:470-521`），创建直升机、空袭、雷达或毒气实体。

## 外观索引链

`Stats_Classes` 为职业定义 `startFrame`：sniper 0、medic 50、tank 100、soldier 150。`Unit.setClass` 在 `Unit.as:292-300` 计算：

```text
frame = startFrame + skin
```

随后 `Unit.as:368` 调用 `UnitMC.setSkin(frame)`。`UnitMC.setSkin`（`UnitMC.as:420-450`）将同一帧索引用于战斗模型的 head、body、手臂、腿和脚；`setPart`（`UnitMC.as:453-456`）只是 `gotoAndStop(index)`。`UnitMC.EnterFrame`（`UnitMC.as:161-168`）把可见 `head` 对齐到隐藏的动画锚点 `headhold`。

三个显示上下文必须区分：

| 上下文 | 已确认入口 | 证据 |
|---|---|---|
| 战斗身体/头部 | `UnitMC.setSkin` | `Unit.as:368`、`UnitMC.as:420-456`；SymbolClass ID 669 映射 `UnitMC` |
| 菜单角色预览 | `Menu.menfr.char.headhold/body/...gotoAndStop(frame)` | `Menu.as:595-602`；SymbolClass ID 905 映射 `MBFZ_fla.ProfileUnit_283` |
| HUD 对话头像 | `mc_speak.head.gotoAndStop(frame)` | `Hud.as:1149,1169` |

`MBFZ_fla.PlayerSelection_282` 对应 SymbolClass ID 889，但它的脚本只声明选择 UI 的文本字段和帧脚本，未发现它直接承载战斗头部素材。具体 sprite 内部的嵌套 character ID 尚未建立完整映射，故“替换哪一个图形标签即可同步全部头像”仍属不确定，必须用 FFDec 时间轴/依赖关系进一步验证。
