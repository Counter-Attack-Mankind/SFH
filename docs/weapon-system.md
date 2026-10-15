# 武器、弹道与伤害系统

## 注册数据

`Stats_Guns.Init` 从 `Stats_Guns.as:138` 开始初始化 `gunOb`、`gunAr` 和六个职业分类数组。USP 的确定注册语句位于 `Stats_Guns.as:149`：

```text
addGun(0, "USP", "", "", "手枪", 0, 1, 15, 3, 0, 0, 0,
       12, 5, 66, 3, false, 0.25, 8, -8, ...)
```

`addGun` 的参数映射由 `Stats_Guns.as:1522-1573` 的赋值直接确认：

| 参数 | 字段 | 含义 |
|---:|---|---|
| 1 | `type` | 武器分类 |
| 2 | `id` | 唯一 ID，也是 `gunOb` 键 |
| 3/4/5 | `sprite/name/typeName` | 显示与类别 |
| 7 | `lvlReq` | 解锁等级 |
| 8 | `dmg` | 基础伤害 |
| 9 | `force` | 冲击/作用力 |
| 10 | `bodBreak` | 存储为参数值乘 0.2 |
| 11/12 | `splash/splashMult` | 爆炸范围及倍率 |
| 13/14 | `clipSize/clipSpare` | 弹匣与备用弹匣数 |
| 15 | `range` | 射程 |
| 16 | `recoil` | 基础后坐力 |
| 17 | `autoFire` | 自动射击 |
| 18 | `shootDelay` | 射击间隔（秒基准） |
| 19/20 | `xOff/yOff` | 枪口偏移 |
| 21-27 | 效果与动画帧 | 射击、命中、弹壳、HUD、idle/fire/reload |
| 28/29/30 | 声音与 `cls` | 射击声、命中声、子弹类 |
| 31/32/33 | `params/extra/desc` | 子弹参数、扩展标志、说明 |

注册后同时写入 `gunOb[id]`、总表 `gunAr` 和 `classAr[type]`。`USP2` 在 `Stats_Guns.as:1471` 是另一条注册，ID 并非 `USP`；补丁器必须区分它。

## 运行时计算

`Guns.setGuns`（`Guns.as:105-177`）由 ID 从 `Stats_Guns.gunOb` 取主副武器。实际总弹药为 `ceil(clipSize * (clipSpare + 1) * unitInfo.amm)`；技能 `clip` 对机枪改变弹药模型。基础配置因此不总等于运行时弹量。

`Guns.EnterFrame`（`Guns.as:325-396`）计算动态后坐力：站立、移动、跳跃、下蹲、反射状态和职业 `aim` 都会修改 `dynRecoilMod`。`Guns.shoot`（`Guns.as:491-641`）检查射击延迟、按键锁、换弹和弹药，然后：

- 播放射击动画并调用 `makeBullet`；`extra.extraShots` 可增加多发射击；
- 令 `shootDelay = curGun.shootDelay * 30`；
- 根据模式和技能扣弹；
- 增加动态后坐力并检查换弹。

`Guns.makeBullet`（`Guns.as:643-661`）以 `curGun.cls` 构造子弹，射角加入 `UT.rand(-dynRecoil, dynRecoilMod)`，并传入武器 ID。具体弹道由 `Bullet_Line_*`、`Bullet_Proj_*`、`Bullet_Melee_Basic` 等子类处理。

命中后，`Bullet.as:313` 和 `Bullet.as:501` 调用：

```text
target.status.damage(stats.dmg * dmgMod, shooter, stats, extra)
```

溅射路径在 `Bullet.as:712-713` 设置 `splashMult` 后调用同一入口。`Status.damage`（`Status.as:166-423`）再应用 AI 难度、友伤、juggernaut、技能、状态、暴击/爆头、爆炸抗性、盾牌与护盾等倍率，最后扣除 `hpCur`。因此 `Stats_Guns.dmg` 是基础伤害，不是每次命中的最终伤害。

## 自动补丁的精确语义

工具定位 `Stats_Guns.Init` 的 ABC method body 11，查找 `findpropstrict addGun` 后第一个武器 ID 参数恰为 `USP` 的注册，并验证随后的空 sprite、空显示名以及第八参数伤害为 `pushbyte 15`。只修改该指令操作数为 100，不修改常量池。当前地址为 `0x89`；地址仅记录审计信息，不作为唯一定位依据。
