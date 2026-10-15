# SFH2 MOD 工程

这是针对现有《Strike Force Heroes 2》SWF 的最小侵入式 MOD 工程。当前已实现并静态验证第一个自动补丁：从只读原版基线出发，将 `Stats_Guns.Init` 中 USP 的基础伤害由 15 改为 100。

## 一键构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

输出：

- `builds/SFH2_USP100.swf`
- `builds/SFH2_USP100.build.json`

静态复核：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify.ps1
```

工程/原版检查：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\inspect.ps1
```

VS Code 已提供 `Build MOD`、`Verify MOD`、`Inspect SWF` 三个任务。

## 工作原理

构建器编译 `src/utilities/SwfGunPatcher.java`，使用项目内 FFDec Java API 读取 SWF 和 AVM2 ABC。定位条件为类名、静态方法名、`addGun` 调用、武器 ID、字段参数序号和预期原值；零匹配、多匹配或原值不符都会失败。输出先写临时文件并重读验证，然后才替换正式产物。反编译 `.as` 仅作证据，不参与重新编译。

## 验证状态

- 静态字节码验证：已通过。
- 自动产物与既有人工测试版 SHA-256 完全一致。
- 运行验证：仍需人工启动游戏，确认正常进入对局且 USP 伤害明显提高。

详细资料见 `docs/project-status.md`、`docs/architecture.md`、`docs/weapon-system.md`、`docs/character-system.md` 和 `docs/modding-feasibility.md`。
