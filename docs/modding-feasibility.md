# 自动化补丁选型与扩展路线

## 已验证的工具能力

本地 FFDec 26.3.0 帮助与实际调用确认：

| 方案 | 本地验证 | 结论 |
|---|---|---|
| CLI P-code 导出 | `-format script:pcode -selectclass Stats_Guns -export script ...` 成功，生成 300,920-byte P-code | 可作审计和差异证据 |
| CLI P-code 回写 | `-replace` 帮助明确支持 AS3 P-code 和 `methodBodyIndex` | 可用，但索引发现和整段文本替换接口较脆弱 |
| AS3 脚本导入 | `-importScript` 存在 | 不选；当前反编译源码含非法中间表示，整类重编译风险高 |
| SWF XML | `-swf2xml/-xml2swf` 存在；原版导出 XML 为 331,374,508 bytes | 粒度过大；仅改一个字节时不适合作主构建路径，未做完整回写往返 |
| FFDec Java API | 实际加载、定位、修改、保存、重读成功 | 当前采用 |
| 自写 ABC 二进制解析/全局字节替换 | 未采用 | 容易误改共享常量、破坏长度/跳转或命中无关 15 |

当前补丁器 `src/utilities/SwfGunPatcher.java` 使用 FFDec 的 SWF/ABC 对象模型，语义定位类、静态方法、`addGun` 注册、武器 ID 和 damage 参数。它要求唯一匹配及原值相符，先写 `.partial`，重读静态验证成功后才原子替换正式输出。它不修改共享常量池，也不依赖 `.as` 可编译。

自动构建结果 `builds/SFH2_USP100.swf` 的 SHA-256 为 `A4ECA56C5FF91E6260318A72DEE95EAAB7E69312F9E5ADDC158CCF16B3E065BA`，与既有人工修改版完全一致。静态验证已通过；游戏启动与实际伤害仍需人工确认。

## 扩展接口与难度

| 需求 | 难度 | 代码入口 | 主要障碍/待验证项 |
|---|---|---|---|
| 武器伤害、弹匣、射程、后坐力、射速 | 低 | `Stats_Guns.Init/addGun` | 为每个字段建立参数序号和类型验证；不同数值可能用不同 push 指令 |
| 多重射击 | 中 | `Stats_Guns.extra.extraShots`、`Guns.shoot` | 先验证已有武器的 `extra` 对象编码和对象字面量 P-code |
| 特殊子弹效果 | 中高 | `Stats_Guns.cls/params/extra`、`Bullet_*` | 复用现有类较容易；新增类需安全注入 ABC，而非单值补丁 |
| HP/职业属性 | 低到中 | `Stats_Classes.getClass`、`Unit.setClass` | 属性是等级插值，必须明确改 min/max 还是最终修正 |
| 移动属性 | 中 | `Movement` 常量及 modifiers | 常量可能被多个状态共用；需按字段赋值语义定位 |
| 替换战斗头部/皮肤帧 | 中高 | `UnitMC.setSkin/setPart`、SymbolClass 669 | 需要建立 sprite、帧、shape/image 依赖表并验证尺寸/注册点 |
| 菜单预览与 HUD 头像 | 高 | ProfileUnit ID 905、PlayerSelection ID 889、`Hud.mc_speak.head` | 三套显示上下文可能不是同一资产；不能假设一次替换全局同步 |
| 调整现有技能参数 | 中 | `Stats_Skills.Init` 加各消费者 | `value` 的单位依技能而异，必须连同消费代码验证 |
| 新增原创技能 | 高 | `Stats_Skills`、`Unit/Status/Guns/Movement` | 需要新增分支或方法，超出单操作数补丁；需可靠 ABC 代码注入方案 |
| AI 数量/难度 | 低到中 | `MatchSettings.useBots/addBot/setBotStats`、`AI.setDiffStats` | 配置生成与菜单上限需分别验证 |
| 对局分数/模式参数 | 中 | `MatchSettings.start* / updateScores` | 修改现有规则简单；新规则要同步 HUD、结束条件与 AI 目标 |
| 随机事件/挑战模式 | 高 | `Game.EnterFrame`、`MatchSettings`、`Stats_Campaign` | 大型方法已混淆，新增控制流需要跳转/栈深验证与充分运行测试 |

## 推荐的下一项原创功能

先做“JSON 驱动的通用武器数值补丁”，支持 `damage`、`shootDelay`、`clipSize`、`range`、`recoil` 五个字段。它复用已验证的 `addGun` 语义定位，风险明显低于资源替换或新增 ABC 逻辑，并能尽快形成可组合、多补丁、冲突检测的构建基础。完成后再做复用现有 `extraShots` 的原创多发武器。

## 版本与资产策略

- `original/`、`decompiled/`、`tools/` 和生成 SWF 默认忽略，不向公开仓库提交。
- Git 中只保存补丁器源码、JSON 配置、脚本、测试和逆向文档。
- 每次构建报告记录输入/输出哈希、目标、原值、新值、method-body 索引和静态/运行验证状态。
- 运行验证必须单独记录；静态成功不得表述为游戏已运行成功。
