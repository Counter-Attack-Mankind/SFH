# Static tests for Avatar Mod v0.3. Runtime death/respawn behaviour remains a manual test.
. (Join-Path $PSScriptRoot '..\scripts\common.ps1')
$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
Compile-SwfPatcher $layout
$classpath = "$($layout.Classes);$($layout.FfdecJar);$($layout.FfdecLib)"
$originalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $layout.Original).Hash
$script:passed = 0
$script:failed = 0

function Run-Avatar([string[]] $Arguments) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $layout.Java -Xmx2g -cp $classpath SwfAvatarPatcher @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    [pscustomobject]@{ ExitCode = $code; Output = (($output | ForEach-Object { [string]$_ }) -join "`n") }
}
function Check([string] $Name, [bool] $Condition, [string] $Detail = '') {
    if ($Condition) { $script:passed++; Write-Host "  PASS  $Name" }
    else { $script:failed++; Write-Host "  FAIL  $Name"; if ($Detail) { Write-Host "        $Detail" } }
}

$config = Join-Path $layout.Root 'src/configs/custom_avatars.json'
$validate = Run-Avatar @('validate', $config, $layout.Root)
Check 'default config validates' ($validate.ExitCode -eq 0) $validate.Output
$build = Run-Avatar @('build', $config, $layout.Root)
Check 'default avatar builds' ($build.ExitCode -eq 0) $build.Output
Check 'five local medic skin frames are installed' ($build.Output -match 'AVATAR_FRAMES=5')
Check 'output re-parses' ($build.Output -match 'OUTPUT_REPARSED=true')
Check 'weapon registry remains identical' ($build.Output -match 'WEAPON_REGISTRY_IDENTICAL=true')
$verify = Run-Avatar @('verify', $config, $layout.Root)
Check 'published output verifies independently' ($verify.ExitCode -eq 0) $verify.Output
Check 'original hash is unchanged' ((Get-FileHash -Algorithm SHA256 -LiteralPath $layout.Original).Hash -eq $originalHash)

$testDir = Join-Path $layout.Root '.cache/tests/avatar'
New-Item -ItemType Directory -Force -Path $testDir | Out-Null
$multiConfig = Join-Path $testDir 'multi.json'
$multiOutput = 'builds/_test_avatar_multi.swf'
$multiJson = @'
{
  "mod_id":"avatar_multi_test",
  "input":"original/SFH_CN_Fixed.swf",
  "output":"builds/_test_avatar_multi.swf",
  "avatars":[
    {"target":{"side":"local_player","class":"medic","skins":[1]},"image":"assets/avatars/medic_head.png"},
    {"target":{"side":"enemy","class":"sniper","skins":[1,2]},"image":"assets/avatars/medic_head.png"},
    {"target":{"side":"ally","class":"tank","skins":[3]},"image":"assets/avatars/medic_head.png"}
  ]
}
'@
[IO.File]::WriteAllText($multiConfig, $multiJson, (New-Object Text.UTF8Encoding($false)))
$multi = Run-Avatar @('build', $multiConfig, $layout.Root)
Check 'multiple classes and identity sides build' ($multi.ExitCode -eq 0) $multi.Output
Check 'multi-rule frame count is four' ($multi.Output -match 'AVATAR_FRAMES=4')

$conflictConfig = Join-Path $testDir 'conflict.json'
$conflictOutput = Join-Path $layout.Root 'builds/_test_avatar_conflict.swf'
Remove-Item -LiteralPath $conflictOutput -Force -ErrorAction SilentlyContinue
$conflictJson = @'
{
  "mod_id":"avatar_conflict_test",
  "input":"original/SFH_CN_Fixed.swf",
  "output":"builds/_test_avatar_conflict.swf",
  "avatars":[
    {"target":{"side":"all","class":"medic","skins":[1]},"image":"assets/avatars/medic_head.png"},
    {"target":{"side":"enemy","class":"medic","skins":[1]},"image":"assets/avatars/medic_head.png"}
  ]
}
'@
[IO.File]::WriteAllText($conflictConfig, $conflictJson, (New-Object Text.UTF8Encoding($false)))
$conflict = Run-Avatar @('build', $conflictConfig, $layout.Root)
Check 'overlapping rules fail closed' ($conflict.ExitCode -ne 0 -and $conflict.Output -match 'overlap') $conflict.Output
Check 'conflict publishes no SWF' (-not (Test-Path -LiteralPath $conflictOutput))
Check 'original remains unchanged after failures' ((Get-FileHash -Algorithm SHA256 -LiteralPath $layout.Original).Hash -eq $originalHash)

Write-Host "avatar-mod tests: $script:passed passed, $script:failed failed"
if ($script:failed) { exit 1 }
