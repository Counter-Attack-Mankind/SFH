# v0.2 武器包工作流

本文档说明如何用"改一个 JSON"的方式调整 SFH2 中任意已注册武器的数值属性，并说明构建器背后的定位算法、数值编码策略、常量池处理和事务语义。

## 1. 数据来源：属性必须来自真实注册调用

游戏里所有枪械都由 `Stats_Guns.Init`（类路径 `Stats_Guns`，静态方法 `Init`，ABC 方法体索引 **11**）通过 `Stats_Guns.addGun(...)` 注册。反编译源码 `decompiled/scripts/Stats_Guns.as:1522` 给出的签名是：

```actionscript
public static function addGun(param1:uint, param2:String, param3:String, param4:String, param5:String,
    param6:int, param7:uint, param8:Number, param9:Number, param10:Number, param11:Number, param12:Number,
    param13:uint, param14:uint, param15:uint, param16:Number, param17:Boolean, param18:Number,
    param19:uint, param20:int, param21:String, param22:String, param23:String, param24:String,
    param25:String, param26:String, param27:String, param28:*, param29:*, param30:*, param31:Array,
    param32:Object, param33:String) : void
```

字段赋值（`Stats_Guns.as:1526-1573`）决定的实参对应关系：

| 用户可见属性 | addGun 形参 | AS3 类型 | 对象字段 | 赋值语句 |
| --- | --- | --- | --- | --- |
| `damage` | param8 | Number | `dmg` | `dmg = param8` |
| `force` | param9 | Number | `force` | `force = param9` |
| `splash` | param11 | Number | `splash` | `splash = param11` |
| `splashMult` | param12 | Number | `splashMult` | `splashMult = param12` |
| `clipSize` | param13 | uint | `clipSize` | `clipSize = param13` |
| `clipSpare` | param14 | uint | `clipSpare` | `clipSpare = param14` |
| `range` | param15 | uint | `range` | `range = param15` |
| `recoil` | param16 | Number | `recoil` | `recoil = param16` |
| `shootDelay` | param18 | Number | `shootDelay` | `shootDelay = param18` |
| `xOff` | param19 | uint | `xOff` | `xOff = param19` |
| `yOff` | param20 | int | `yOff` | `yOff = param20` |

`param6` 在函数体内**从未被使用**；`param10` 被存成 `bodBreak = param10 * 0.2`（运行时变换过，所以原始实参不是运行时值）；`param7` 同时喂给 `lvlReq` 和 `Stats_Classes.getItemCost(param7)`。这三个因此被明确**拒绝**修改，而不是猜测语义。

`shootDelay` 在 `Guns.shoot` 中被换算为 `curGun.shootDelay * 30` 帧延迟，**数值越大射速越慢**。

`dmg` 是基础伤害：`Bullet.as:313` / `Bullet.as:501` 调用 `target.status.damage(stats.dmg * dmgMod, ...)`，随后 `Status.damage` 还会叠加 AI 难度、暴击、爆头、护盾等修正，所以改大 `dmg` 不会得到等比例的最终伤害。

### 支持范围

| 属性 | 类型 | 合法区间 |
| --- | --- | --- |
| `damage` | Number | 0 .. 1000000 |
| `shootDelay` | Number | 0 .. 3600 |
| `clipSize` | uint | 0 .. 1000000（不可为负） |
| `recoil` | Number | -1000 .. 1000 |
| `range` / `clipSpare` / `xOff` | uint | 0 .. 1000000 / 100000 |
| `force` / `splashMult` | Number | -1000000 .. 1000000 / -1000 .. 1000 |
| `splash` | Number | 0 .. 1000000 |
| `yOff` | int | -100000 .. 100000 |

用 `scripts/list-weapons.ps1 -Fields` 可直接打印这张表和被拒绝的字段及其原因。

## 2. 武器与实参定位算法

反编译产物不可信：`Init` 里存在大量被 `jump` 跳过的死代码块和 `pushfalse; iffalse` / `pushtrue; iftrue` 形式的不透明谓词，FFDec 因此把它们渲染成 `§§push` / `§§pop`。构建器完全基于真实 AVM2 字节码工作。

1. 用 `swf.getAS3Packs()` + `getPathScriptName()` 选中类路径为 `Stats_Guns` 的脚本包（必须唯一），`abc.findClassByName` 取类，在其静态 trait 中按名字取方法，`abc.findBodyIndex` 得到方法体。
2. **常量传播的可达性分析**：从入口开始遍历，`jump` / `iffalse` / `iftrue` / `ifnlt` 按 `ins.getTargetAddress()` 解析目标；当一个分支弹出的条件是已知常量时，只沿真实方向继续，从而剔除不透明谓词保护的死块。
3. **操作数栈抽象解释**：沿可达路径模拟 AVM2 操作数栈，每个栈槽记录"由哪条指令产生"。`dup` 复制槽位（而不是当成新的 push），所以共享同一产生者的相邻实参会被识别为同一个修改点。
4. 每次遇到 `callpropvoid` 且多名字为 `addGun`（多名字索引 2500）时，其 `operands[1]` 即实参个数（**33**）。从栈里弹出 33 个槽位，`paramN` 对应第 N 个实参。
5. 武器 ID 取 `param2` 的 `pushstring` 字面量，**精确相等**匹配（因此 `USP` 永远匹配不到 `USP2`，二者是两处独立注册）。ID 不存在时给出近似建议；ID 重复出现时直接报错。
6. 只有当目标实参来自编译期常量、且该常量的产生指令是纯数值入栈时才允许修改；否则明确拒绝（"not a compile-time constant" / "not a plain numeric constant"）。

在原始 SWF 上该算法解析出 **81** 次 `addGun` 调用、**81** 把武器、共 **2673** 个实参，并与人工阅读反编译源码得到的基准值逐项自检通过（`RESOLVER_SELF_TEST=OK`）。

`list-weapons` 输出示例（制表符分隔）：

```
WEAPON  USP  124  0x104  1=0,2="USP",3="",4="",5="手枪",6=0,7=1,8=15,9=3,10=0.0,11=0,12=0,13=12,...
WEAPON  USP2 6165 0x322c 1=5,2="USP2",3="USP",4="USP",...,8=15,...,13=1,...
```

## 3. 数值编码与常量池

改写不再是"往字节数组里塞数字"，而是构造新的 `AVM2Instruction` 并用 `AVM2Code.replaceInstruction(pos, ins, body)` 替换，交由 FFDec 重算指令地址、分支偏移、异常表范围和方法元数据。

编码选择（`Avm2ConstantEditor.encode`）：

| 目标值 | 选用指令 |
| --- | --- |
| -128 .. 127 | `pushbyte` |
| -32768 .. 32767 | `pushshort` |
| int 范围内 | `pushint`（追加 int 常量池条目） |
| 0 .. 4294967295 | `pushuint`（追加 uint 常量池条目） |
| 其它 / 小数 | `pushdouble`（追加 double 常量池条目） |

**共享常量池绝不就地修改。** `pushdouble` 的 double 是池内共享的：例如 `0.25` 是池中第 23 号条目，USP、USP2 等多把枪都引用它。修改时一律 `addDouble` 追加新条目，然后只把目标指令的 operand 指向新下标。整数（`pushbyte` / `pushshort`）的字面量在指令操作数里，不涉及共享。

实测编码结果：

```
USP.damage    (param8):   15 -> 500   via pushshort 500   [2 -> 3 bytes]
USP.clipSize  (param13):  12 -> 30    via pushbyte 30     [2 -> 2 bytes]
USP.shootDelay(param18): 0.25 -> 0.1  via pushdouble 0.1 (double #81, appended)
USP.recoil    (param16):   3 -> 0     via pushbyte 0
M4.damage     (param8):   10 -> 200   via pushshort 200
M4.range      (param15):  60 -> 300   via pushshort 300
```

边界实测（`.cache/tests` 中的探针）：`127`/`128`/`32767`/`32768`/`4294967295`/`4294967296`/`-1`/`-129`/`0` 全部可写回并重新解析得到同一数值。指令长度变化（`pushbyte`→`pushshort`，2→3 字节）后，方法体内所有分支目标仍然正确。

无法安全改写的结构一律 fail-closed：非数值产生指令、非常量实参、改写后指令条数发生变化，都会中止整个构建。

## 4. 事务语义（原子性）

`WeaponPackPatcher.build` 的顺序：

1. 解析并校验 JSON（重复成员、必填项、未知/被拒字段、取值范围、重复武器、重复字段、无意义修改）。此阶段不打开 SWF。
2. 打开原版 SWF，解析全部 81 处注册，跑解析器自检。
3. 建立"产生指令 → 消费者"映射；对每条修改确认目标可改写、`expected` 与原值一致。
4. 检查冲突：解析到同一条指令的多条修改必须写入同一个值；该指令若还喂给未在配置中的实参，直接拒绝。
5. 按产生指令位置从后往前替换，避免下标漂移；替换后指令条数必须不变。
6. 写入临时产物 `<output>.partial`。
7. 重新解析临时产物，逐项复核：武器集合不变、只有配置指定的实参变化、每个目标实参等于新值。
8. 全部通过才 `ATOMIC_MOVE` 到正式产物，并写构建报告。
9. 任何一步抛异常：删除临时产物、删除正式产物、删除旧构建报告（避免留下误导性的成功报告）。

## 5. 静态复核与隔离比对

`verify-config` 做三件事：

1. 重新解析产物，逐个打印目标属性的实际值（例如 `VERIFIED USP.damage = 500 (addGun param8, pushshort)`）。
2. 重新解析**原版**，与产物做全量实参比对，要求差异集合恰好等于配置里的修改集合（`ISOLATION_CHECK=OK`、`CHANGED_ARGUMENTS=6`）。这一步能抓出"误改了共享常量导致别的枪一起变"。
3. 核对原版当前 SHA-256 与构建报告里记录的 `input_sha256`，证明原版文件没有被改动（`ORIGINAL_UNMODIFIED=OK`）。

## 6. 自动化测试

`scripts/test.ps1` 依次运行：

- `tests/test-usp-patch.ps1`：v0.1 回归。USP `15 → 100` 与 `15 → 127` 仍可构建、仍可复核；重复构建 SHA-256 一致；原值不匹配时非零退出且不留产物；两个历史配置文件仍能通过 `scripts/build.ps1` 构建。
- `tests/test-weapon-pack.ps1`：88 项断言。覆盖多武器多属性、大于 127、浮点、确定性、10 类非法配置的 fail-closed 行为与产物清理、写目标安全、静态验证失败路径、以及完整性检查（原版 SHA-256 不变、产物可被 FFDec 重新解析、报告内容正确）。

这些全部是**静态**检查：它们证明产物的字节码描述了所要求的数值，不证明游戏运行表现。真实运行仍需人工测试。

## 7. 人工运行测试清单

1. 用产出的 `builds/SFH2_CustomWeapons.swf` 启动游戏。
2. 进入一局，装备 USP，确认伤害面板/实际击杀速度大幅提升，弹匣 30 发。
3. 确认 USP 射速明显变快（`shootDelay` 0.25 → 0.1），且**没有**后坐力（`recoil` 3 → 0）。
4. 装备 USP2（同名的"升级版"手枪），确认它仍是原版手感：伤害 15、单发弹匣、无后坐力变化——用来验证共享 double 常量未被污染。
5. 装备 M4，确认伤害提升且射程变远。
6. 随便再选一两把未在配置中出现的武器，确认手感与改动前一致。
