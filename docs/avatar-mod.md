# Avatar Mod v0.3

Avatar Mod changes battle-character heads without replacing the original shared head Shapes. It
imports each configured image once, creates one fitted Shape per selected skin, installs those
Shapes in reserved frames of head Sprite 666, and injects an instance-level selector into
`UnitMC.setSkin`.

## Build and configuration

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-avatar.ps1 `
  -Config "src/configs/custom_avatars.json"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate-avatar.ps1 `
  -Config "src/configs/custom_avatars.json"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-avatar.ps1 `
  -Config "src/configs/custom_avatars.json"
```

```json
{
  "mod_id": "custom_avatar_pack",
  "input": "original/SFH_CN_Fixed.swf",
  "output": "builds/SFH_CustomAvatars.swf",
  "avatars": [
    {
      "target": {"side": "local_player", "class": "medic", "skins": "all"},
      "image": "assets/avatars/medic_head.png"
    },
    {
      "target": {"side": "enemy", "class": "sniper", "skins": [1, 2]},
      "image": "assets/avatars/enemy_sniper.jpg"
    }
  ]
}
```

- `class`: `medic`, `sniper`, `soldier`, or `tank`.
- `side`: `local_player`, `ally`, `enemy`, or `all`.
- `ally` is a non-player unit on the player's non-zero team. Team 0 is free-for-all, so its AI
  units are enemies.
- `skins`: `"all"` or unique integers 1–5.
- `image`: PNG, JPG, or JPEG. PNG alpha is preserved; images without alpha are imported opaque.
- Conflicting identity/class/skin domains are rejected; there is no implicit precedence.
- Output must remain below `builds/`. It is published only after a `.partial` file re-parses and
  passes isolation verification.

To use `lsj.png`, copy it into `assets/avatars/` and change only `image`; Java code does not change.

## Verified original resource mapping

These are direct `PlaceObject` results from DefineSprite 666. A Shape is shared by every unit
instance selecting that class and skin; it is not a local-player identity resource.

| Class | startFrame | skin | Sprite frame | Shape ID | Shared |
|---|---:|---:|---:|---:|---|
| sniper | 0 | 1 | 1 | 632 | yes |
| sniper | 0 | 2 | 2 | 633 | yes |
| sniper | 0 | 3 | 3 | 634 | yes |
| sniper | 0 | 4 | 4 | 635 | yes |
| sniper | 0 | 5 | 5 | 636 | yes |
| medic | 50 | 1 | 51 | 639 | yes |
| medic | 50 | 2 | 52 | 640 | yes |
| medic | 50 | 3 | 53 | 641 | yes |
| medic | 50 | 4 | 54 | 642 | yes |
| medic | 50 | 5 | 55 | 643 | yes |
| tank | 100 | 1 | 101 | 650 | yes |
| tank | 100 | 2 | 102 | 651 | yes |
| tank | 100 | 3 | 103 | 652 | yes |
| tank | 100 | 4 | 104 | 653 | yes |
| tank | 100 | 5 | 105 | 654 | yes |
| soldier | 150 | 1 | 151 | 657 | yes |
| soldier | 150 | 2 | 152 | 658 | yes |
| soldier | 150 | 3 | 153 | 659 | yes |
| soldier | 150 | 4 | 154 | 660 | yes |
| soldier | 150 | 5 | 155 | 661 | yes |

### Runtime identity mapping

| Mode/identity | Skin rule | Can a Shape isolate identity? |
|---|---|---|
| Team quickmatch local player | skin 2, team 1 | no; shared with allied bots |
| Team quickmatch allied bot | team 1 gives skin 2 | no |
| Team quickmatch enemy bot | team 2 gives skin 3 | only by mode convention |
| FFA local player | saved skin 1–5 | no |
| FFA AI | random skin 1–5, team 0 | no |
| Campaign player/AI | campaign data or saved skin | not globally static |
| `changeTeam` | normally `team + 1`; some mod/team-2 cases use 5 | changes at runtime |

The old Shapes 639–643 patch therefore changed enemy medics: it replaced all medic skins, not a
player identity. v0.3 leaves them intact and checks `Unit.human`, team identity, original class
frame, and skin on each `UnitMC` instance.

## Display and respawn behaviour

- `UnitMC.head` and hidden `headhold` both use Sprite 666. `headhold` remains the animation anchor;
  the patch only changes the visible head frame.
- Existing UnitMC `scaleX` flipping includes the head, so no per-frame object creation is needed.
- Respawn executes `spawn -> unitSpawn -> setClass -> setSkin -> goto("idle")`; selection is
  reapplied after every respawn and class change.
- Menu `ProfileUnit.headhold` and HUD `mc_speak.head` also use Sprite 666, but lack the same reliable
  identity context. v0.3 deliberately leaves them on their original skin instead of misclassifying
  them as local/ally/enemy.

## Verification and manual runtime test

Static verification proves: output re-parses; legal original frames keep their original Shapes;
new Shapes reference one legal bitmap; every original Character except Sprite 666 is byte-identical;
`UnitMC.setSkin` contains the instance selector; and all `addGun` registrations retain their values.

It cannot prove movement or runtime compatibility. Perform this acceptance test in Flash/Ruffle:

1. In a team match as medic, verify only the player has the custom head.
2. Verify allied and enemy medics retain original heads.
3. Face both directions; move, shoot, reload, crouch, and jump.
4. Die and respawn, then repeat movement, shooting, and jumping.
5. Kill an enemy medic, wait for its respawn, and verify it moves and shoots again.
6. Repeat in FFA and, if relevant, campaign and team-changing modifiers.
7. Menu and HUD speech portraits should remain original in v0.3.

Until this is completed in a real runtime, the build report correctly says
`runtime_status: manual_test_required`.
