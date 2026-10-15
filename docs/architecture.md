# 游戏架构与生命周期

本文件记录从实际反编译代码确认的调用关系。行号对应当前 `decompiled/scripts` 导出版本。

## 启动与场景切换

入口并非公开的 `Main.Init()`：`Main` 构造器在 `Main.as:89-104` 注册 `ADDED_TO_STAGE`，事件进入私有 `Main.Init(Event)`（`Main.as:107`）。该方法在 `Main.as:139-170` 安装帧、鼠标、键盘与激活事件，然后依次初始化 `SD`、`SH`、`Stats_Classes`、`Stats_Guns`、`Stats_Maps`、`Stats_Skills`、`Stats_Streaks`、`Stats_Misc`、`MatchSettings` 和成就系统。

加载流程为：

```text
Main() -> Main.Init(Event)
       -> LoaderScreen / GameVars.Load
       -> loadDone() -> BitScreen
       -> renderDone() -> logo 链或 Menu
       -> startClass(nextClass, options)
```

`Main.startClass`（`Main.as:310-324`）移除当前 `curClass`，构造新场景并加入显示列表。`Main.EnterFrame`（`Main.as:241-251`）把每帧更新转发给 `curClass.EnterFrame()` 和 `SH.EnterFrame()`。菜单与战斗均通过这个统一接口工作。战斗销毁后，`Game.destroy`（`Game.as:615-634`）按剧情条件进入过场，否则回到 `Menu`。

## 对局建立

`Game` 构造器（`Game.as:111-129`）建立天空、背景、`MatchSettings`、地图粒子、物理世界、水体和场景。地图对象完成后调用 `Game.InitGame`（`Game.as:132-179`）：

1. 建立瞄准器、位图屏幕、单位/线条容器及 `effects`、`bullets`、`units`、`killstreaks` 数组。
2. 建立 `Radar` 和 `Hud`。
3. 由 `MatchSettings.usePlayer` 构造一个 `Player`，再遍历 `useBots` 构造 `AI`。
4. 将所有单位经 `Game.createUnit` 加入显示树和 `units` 数组（`Game.as:452-468`）。
5. 初始化特殊 juggernaut、对局计分规则和地图粒子。

`Game.startGame` 只设置 `gameStarted=true`、`paused=false`（`Game.as:181-185`）。`Game.EnterFrame`（`Game.as:207-364`）在未暂停时按顺序更新地图/物理、连杀实体、HUD、单位、拾取物、子弹、效果、`MatchSettings`、雷达和水体；子弹与效果的删除也在此完成。暂停时仍更新 HUD。

`MatchSettings.startQuickmatch` / `startCampaign`（`MatchSettings.as:315-375`）把编辑态配置复制到 `useMap/useMode/useScore/useMod/useSkills/useStreaks/useBots/usePlayer`。实例 `Init`（`MatchSettings.as:444-486`）建立队伍数组和 HUD 计分条；`updateScores`（`MatchSettings.as:532-664`）实现 `dm/zom/jug/tdm/dom/ctf` 的结束条件，并调用 `Game.endGame`。`Game.endGame`（`Game.as:187-205`）锁定结束状态并启动 HUD 结束动画。

## 单位对象组合

`Player` 和 `AI` 均继承 `Unit`。`Unit` 构造器（`Unit.as:183-248`）组合：

```text
Unit
├── UnitMC    动画和身体部件
├── Movement  移动、碰撞、跳跃
├── Guns      武器、弹药、开火
├── Status    生命、护盾、状态、伤害
└── Score     击杀与计分
```

`Player.EnterFrame`（`Player.as:100-172`）读取鼠标、输入和瞄准，随后调用 `UnitEnterFrame`。`AI.setDiffStats`（`AI.as:112-189`）把难度映射为等待概率、射击概率和瞄准速度；AI 的导航入口包括 `getNextWaypoint`、`pathFind` 和 `moveToObjective`。两者最终共享 `Unit`、`Movement`、`Guns` 与 `Status` 的运行逻辑。

## 已知不确定项

- 反编译器插入的 `§§push/§§pop` 令部分分支次序看起来异常，尤其是 `Game.EnterFrame` 的随机换队段和大型注册函数。文档不重构这些分支。
- `Unit.setDiffStats`、`spawn`、`getNextWaypoint` 在基类为空实现，由 `Player`/`AI` 覆盖；不能把基类空方法误判为功能缺失。
