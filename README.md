# SFH2 MOD 工程

针对现有《Strike Force Heroes 2》SWF 的最小侵入式 MOD 工程。基于项目内置的 JPEXS FFDec Java API 直接改写 AVM2 字节码，不重新编译游戏、不覆盖原版文件、不修改 `decompiled/` 中的参考源码。

## 当前能力（v0.2）

改一个 JSON 文件，即可同时调整多把已注册武器的多个数值属性，并生成经过静态验证的 MOD SWF。

- 任意已注册武器的精确 ID 定位（`USP` 不会误匹配 `USP2`）。
- 支持属性：`damage`、`shootDelay`、`clipSize`、`recoil`，以及 `range`、`force`、`clipSpare`、`splash`、`splashMult`、`xOff`、`yOff`。
- 数值不再受 `pushbyte` 的 ±127 限制，自动在 `pushbyte` / `pushshort` / `pushint` / `pushuint` / `pushdouble` 之间选择合法编码。
- 单个 JSON 支持任意数量武器、每把武器任意数量属性。
- 批量构建具备完整事务语义：任何一项失败都不会发布正式产物，也不会留下误导性的旧报告。
- 构建后重新解析产物，逐个复核目标属性；并重新解析原版做隔离比对，证明只有配置指定的实参发生变化。

## 一键构建（批量武器包）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build.ps1 -Config "src/configs/custom_weapon_pack.json"
```

输出：

- `builds/SFH2_CustomWeapons.swf`
- `builds/SFH2_CustomWeapons.swf.build.json`

静态复核（会额外做原版隔离比对，并确认原版 SHA-256 未变）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify.ps1 -Config "src/configs/custom_weapon_pack.json"
```

只校验配置、不写任何文件（会解析原版、确认每个 `expected` 原值、打印每条修改将使用的字节码编码）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate-config.ps1 -Config "src/configs/custom_weapon_pack.json"
```

列出原版中全部已被解析的武器注册与原始属性值（只读，用于查真实武器 ID 和原值）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\list-weapons.ps1 -Filter 'USP'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\list-weapons.ps1 -Fields
```

工程/原版检查：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\inspect.ps1 -Config "src/configs/custom_weapon_pack.json"
```

全部自动化测试（v0.1 回归 + v0.2 批量）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test.ps1
```

VS Code 提供任务：`Build MOD`、`Build MOD (weapon pack)`、`Verify MOD`、`Verify MOD (weapon pack)`、`Validate Config`、`List Weapons`、`Inspect SWF`、`Test MOD`。

## 配置格式

v0.2 批量格式（`weapons` 数组的存在即代表使用该格式）：

```json
{
  "mod_id": "custom_weapon_pack",
  "description": "说明文字",
  "input": "original/SFH_CN_Fixed.swf",
  "output": "builds/SFH2_CustomWeapons.swf",
  "weapons": [
    {
      "id": "USP",
      "changes": {
        "damage":    { "expected": 15,   "value": 500 },
        "clipSize":  { "expected": 12,   "value": 30  },
        "shootDelay":{ "expected": 0.25, "value": 0.1 },
        "recoil":    { "expected": 3,    "value": 0   }
      }
    },
    {
      "id": "M4",
      "changes": {
        "damage": { "expected": 10, "value": 200 },
        "range":  { "expected": 60, "value": 300 }
      }
    }
  ]
}
```

规则：

- `expected` 是必须核对的原始值；与 SWF 中实际值不符即整体失败。
- 同一把武器的同一字段只能出现一次；同名 JSON 成员重复也会被拒绝。
- `expected` 与 `value` 相同视为无意义修改，直接拒绝。
- 不支持或无法安全定位的字段会明确报错并给出原因，不会猜测。
- `output` 必须位于 `builds/` 之下，且不得等于 `input`。

v0.1 单补丁格式仍然完全支持，历史配置 `src/configs/usp_damage_100.json` 与 `src/configs/usp_damage_test.json` 均照旧可用。

## 验证状态

- 静态字节码验证：已通过（批量构建后逐字段重新解析复核）。
- 隔离比对：只有配置指定的实参发生变化（81 把武器 / 2673 个实参全部逐一比对）。
- 原版 `original/SFH_CN_Fixed.swf` 的 SHA-256 全程未变：`B4FFEAD005A6624A8A0942FA6AAF10AB114F1C4777EE6F2F5DE8C6E5A602C0DE`。
- 运行验证：仍需人工启动游戏确认。

静态检查不能替代真实游戏运行验证。

详细资料见 `docs/project-status.md`、`docs/architecture.md`、`docs/weapon-system.md`、`docs/weapon-pack-workflow.md`。
