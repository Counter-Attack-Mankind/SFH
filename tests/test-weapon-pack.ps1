# Automated test suite for the v0.2 batch weapon-pack patcher.
#
# Everything here is a STATIC check: it proves the bytecode of the produced SWF describes the
# requested attribute values, that nothing else changed, and that the original game file is
# untouched. It does NOT prove the game behaves correctly at runtime - that still needs a human
# to launch the game with the built SWF.

. (Join-Path $PSScriptRoot '..\scripts\common.ps1')

$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
Compile-SwfPatcher $layout

$configDir = Join-Path $layout.Root '.cache/tests/configs'
New-Item -ItemType Directory -Force -Path $configDir | Out-Null

$script:passed = 0
$script:failed = 0

function Get-OriginalSha {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $layout.Original).Hash
}

function New-TestConfig {
    param(
        [string] $Name,
        [string] $ModId,
        [string] $Output,
        [string] $WeaponsJson
    )
    $json = @"
{
  "mod_id": "$ModId",
  "description": "automated test case $Name",
  "input": "original/SFH_CN_Fixed.swf",
  "output": "$Output",
  "weapons": $WeaponsJson
}
"@
    $path = Join-Path $configDir "$Name.json"
    # Write without a BOM; the patcher tolerates one, but keeping the fixture clean means the test
    # exercises the same bytes a hand-edited config would produce.
    [IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
    return $path
}

# Java writes its failure summary to stderr, which PowerShell would otherwise turn into a
# terminating error before the test can inspect it. Capture both streams with stderr non-fatal.
function Invoke-JavaCapture {
    param([string[]] $Arguments)
    $classpath = "$($layout.Classes);$($layout.FfdecJar);$($layout.FfdecLib)"
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $layout.Java -Xmx2g -cp $classpath SwfGunPatcher @Arguments 2>&1)
        return @{
            ExitCode = $LASTEXITCODE
            Output   = ($lines | ForEach-Object { [string]$_ }) -join "`n"
        }
    } finally {
        $ErrorActionPreference = $saved
    }
}

function Invoke-JavaPatch {
    param([string] $ConfigPath)
    return Invoke-JavaCapture @('patch-config', $ConfigPath, $layout.Root)
}

function Invoke-JavaTool {
    param([string[]] $Arguments)
    return Invoke-JavaCapture $Arguments
}

function Assert-True {
    param([string] $Name, [bool] $Condition, [string] $Detail = '')
    if ($Condition) {
        $script:passed++
        Write-Host "  PASS  $Name"
    } else {
        $script:failed++
        Write-Host "  FAIL  $Name"
        if ($Detail) {
            Write-Host "        $Detail"
        }
    }
}

function Assert-NoArtifact {
    param([string] $Name, [string] $RelativeOutput)
    $output = [IO.Path]::GetFullPath((Join-Path $layout.Root $RelativeOutput))
    $report = "$output.build.json"
    Assert-True "$Name : no official SWF was published" (-not (Test-Path -LiteralPath $output)) $output
    Assert-True "$Name : no build report was left behind" (-not (Test-Path -LiteralPath $report)) $report
    Assert-True "$Name : no partial file was left behind" (-not (Test-Path -LiteralPath "$output.partial")) "$output.partial"
}

$originalShaBefore = Get-OriginalSha

# ---------------------------------------------------------------- 1. happy path
Write-Host ''
Write-Host 'Numeric values: multi-weapon, multi-field, mixed integer and floating point'

$happyConfig = Join-Path $layout.Root 'src/configs/custom_weapon_pack.json'
$happyOutput = 'builds/SFH2_CustomWeapons.swf'
Remove-Item -LiteralPath (Join-Path $layout.Root "$happyOutput") -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $layout.Root "${happyOutput}.build.json") -Force -ErrorAction SilentlyContinue

$happy = Invoke-JavaPatch $happyConfig
Assert-True 'weapon pack build succeeds' ($happy.ExitCode -eq 0) $happy.Output
Assert-True 'build reports no unexpected differences' ($happy.Output -match 'UNEXPECTED_DIFFERENCES=0')
Assert-True 'build reports six changes' ($happy.Output -match 'CHANGES=6')
Assert-True 'built SWF exists' (Test-Path -LiteralPath (Join-Path $layout.Root $happyOutput))

$verify = Invoke-JavaTool @('verify-config', $happyConfig, $layout.Root)
Assert-True 'verify-config succeeds on the built SWF' ($verify.ExitCode -eq 0) $verify.Output
$happyModel = Get-Content -Raw -Encoding UTF8 -LiteralPath $happyConfig | ConvertFrom-Json
$expectedVerified = foreach ($weapon in $happyModel.weapons) {
    foreach ($field in $weapon.changes.PSObject.Properties) {
        "VERIFIED $($weapon.id).$($field.Name) = $($field.Value.value)"
    }
}
foreach ($expected in $expectedVerified) {
    Assert-True "re-parsed value: $expected" ($verify.Output -match [regex]::Escape($expected))
}
Assert-True 'isolation check passes' ($verify.Output -match 'ISOLATION_CHECK=OK')
Assert-True 'exactly six arguments differ from the original' ($verify.Output -match 'CHANGED_ARGUMENTS=6')
Assert-True 'original file is unchanged since the build' ($verify.Output -match 'ORIGINAL_UNMODIFIED=OK')

# ---------------------------------------------------------------- 2. >127 and float coverage
Write-Host ''
Write-Host 'Numeric encoding: values above 127 and floating point'

$bigConfig = New-TestConfig -Name 'big-values' -ModId 'test_big_values' -Output 'builds/_test_big.swf' -WeaponsJson @'
[
  {"id":"USP","changes":{"damage":{"expected":15,"value":32767}}},
  {"id":"USP2","changes":{"damage":{"expected":15,"value":200}}}
]
'@
$big = Invoke-JavaPatch $bigConfig
Assert-True 'pushshort-range values build' ($big.ExitCode -eq 0) $big.Output
Assert-True 'large damage is encoded as pushshort' ($big.Output -match 'pushshort 32767')
Assert-True '200 is encoded as pushshort' ($big.Output -match 'pushshort 200')
$bigVerify = Invoke-JavaTool @('verify-config', $bigConfig, $layout.Root)
Assert-True 'large values re-parse correctly' ($bigVerify.ExitCode -eq 0) $bigVerify.Output
Assert-True 'USP2 damage is exactly 200' ($bigVerify.Output -match 'VERIFIED USP2.damage = 200')
Assert-True 'the two pistols did not share one instruction' ($bigVerify.Output -match 'VERIFIED USP.damage = 32767')

$hugeConfig = New-TestConfig -Name 'huge-values' -ModId 'test_huge_values' -Output 'builds/_test_huge.swf' -WeaponsJson @'
[
  {"id":"USP","changes":{"damage":{"expected":15,"value":1000000}}}
]
'@
$huge = Invoke-JavaPatch $hugeConfig
Assert-True 'very large values build' ($huge.ExitCode -eq 0) $huge.Output
Assert-True 'very large value uses the constant pool' ($huge.Output -match 'pushuint|pushdouble|pushint')

# A config saved by Notepad or PowerShell normally starts with a UTF-8 byte-order mark.
$bomConfig = New-TestConfig -Name 'bom-config' -ModId 'test_bom_config' -Output 'builds/_test_bom.swf' -WeaponsJson @'
[
  {"id":"USP","changes":{"damage":{"expected":15,"value":90}}}
]
'@
$bomText = Get-Content -Raw -Encoding UTF8 -LiteralPath $bomConfig
[IO.File]::WriteAllText($bomConfig, $bomText, (New-Object System.Text.UTF8Encoding($true)))
$bom = Invoke-JavaPatch $bomConfig
Assert-True 'a byte-order-marked config is accepted' ($bom.ExitCode -eq 0) $bom.Output

# ---------------------------------------------------------------- 3. determinism
Write-Host ''
Write-Host 'Determinism: the same configuration built twice produces identical bytes'

$first = Invoke-JavaPatch $happyConfig
$firstSha = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $layout.Root $happyOutput)).Hash
$second = Invoke-JavaPatch $happyConfig
$secondSha = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $layout.Root $happyOutput)).Hash
Assert-True 'repeat build succeeds' ($first.ExitCode -eq 0 -and $second.ExitCode -eq 0)
Assert-True 'repeat build is byte-identical' ($firstSha -eq $secondSha) "$firstSha vs $secondSha"

# ---------------------------------------------------------------- 4. rejection cases
Write-Host ''
Write-Host 'Rejected configurations (each must fail closed and publish nothing)'

$cases = @(
    @{ Name = 'unknown-weapon'; Output = 'builds/_test_unknown_weapon.swf'; Match = "is not registered"
       Weapons = '[{"id":"NotAWeapon","changes":{"damage":{"expected":15,"value":50}}}]' },
    @{ Name = 'unknown-field'; Output = 'builds/_test_unknown_field.swf'; Match = "unknown field"
       Weapons = '[{"id":"USP","changes":{"damagee":{"expected":15,"value":50}}}]' },
    @{ Name = 'refused-field'; Output = 'builds/_test_refused_field.swf'; Match = "cannot be patched"
       Weapons = '[{"id":"USP","changes":{"bodBreak":{"expected":0,"value":50}}}]' },
    @{ Name = 'value-out-of-range'; Output = 'builds/_test_range.swf'; Match = "invalid"
       Weapons = '[{"id":"USP","changes":{"clipSize":{"expected":12,"value":-5}}}]' },
    @{ Name = 'wrong-expected'; Output = 'builds/_test_wrong_expected.swf'; Match = "expects the original value"
       Weapons = '[{"id":"USP","changes":{"damage":{"expected":14,"value":50}}}]' },
    @{ Name = 'duplicate-weapon'; Output = 'builds/_test_dup_weapon.swf'; Match = "more than once"
       Weapons = '[{"id":"USP","changes":{"damage":{"expected":15,"value":50}}},{"id":"USP","changes":{"recoil":{"expected":3,"value":1}}}]' },
    @{ Name = 'duplicate-field'; Output = 'builds/_test_dup_field.swf'; Match = "more than once"
       Weapons = '[{"id":"USP","changes":{"damage":{"expected":15,"value":50},"damage":{"expected":15,"value":60}}}]' },
    @{ Name = 'no-op-change'; Output = 'builds/_test_noop.swf'; Match = "no-op"
       Weapons = '[{"id":"USP","changes":{"damage":{"expected":15,"value":15}}}]' },
    @{ Name = 'empty-weapons'; Output = 'builds/_test_empty.swf'; Match = "at least one entry"
       Weapons = '[]' },
    @{ Name = 'null-weapon'; Output = 'builds/_test_null_weapon.swf'; Match = "must not be empty"
       Weapons = '[{"id":"","changes":{"damage":{"expected":15,"value":50}}}]' }
)

foreach ($case in $cases) {
    $config = New-TestConfig -Name $case.Name -ModId "test_$($case.Name)" -Output $case.Output -WeaponsJson $case.Weapons
    $result = Invoke-JavaPatch $config
    Assert-True "$($case.Name) is rejected" ($result.ExitCode -ne 0) $result.Output
    Assert-True "$($case.Name) reports the expected reason" ($result.Output -match $case.Match) $result.Output
    Assert-NoArtifact $case.Name $case.Output
}

# ---------------------------------------------------------------- 5. write-target safety
Write-Host ''
Write-Host 'Write safety: the original SWF must never be the build target'

$selfConfig = New-TestConfig -Name 'output-is-input' -ModId 'test_output_is_input' `
    -Output 'original/SFH_CN_Fixed.swf' -WeaponsJson '[{"id":"USP","changes":{"damage":{"expected":15,"value":50}}}]'
$self = Invoke-JavaPatch $selfConfig
Assert-True 'writing over the original is rejected' ($self.ExitCode -ne 0) $self.Output
Assert-True 'rejection names the original file' ($self.Output -match 'must not be the original')
Assert-True 'original SWF still has its build-time SHA-256' ((Get-OriginalSha) -eq $originalShaBefore)

$outside = New-TestConfig -Name 'output-outside-builds' -ModId 'test_output_outside' `
    -Output 'tmp/_test_outside.swf' -WeaponsJson '[{"id":"USP","changes":{"damage":{"expected":15,"value":50}}}]'
$outsideResult = Invoke-JavaPatch $outside
Assert-True 'building outside builds/ is rejected' ($outsideResult.ExitCode -ne 0) $outsideResult.Output

# ---------------------------------------------------------------- 6. static verification failure
Write-Host ''
Write-Host 'Static verification failure: verifying an unmodified SWF against a patched config'

$wrongTarget = Invoke-JavaTool @('verify-config', $happyConfig, $layout.Root, $layout.Original)
Assert-True 'verifying the original against the pack config fails' ($wrongTarget.ExitCode -ne 0) $wrongTarget.Output
Assert-True 'failure names the offending attribute' ($wrongTarget.Output -match 'USP') $wrongTarget.Output

# ---------------------------------------------------------------- 7. integrity
Write-Host ''
Write-Host 'Integrity'

Assert-True 'original SWF SHA-256 is unchanged' ((Get-OriginalSha) -eq $originalShaBefore)
Assert-True 'the built SWF re-parses under FFDec' ($verify.ExitCode -eq 0)
$reportPath = Join-Path $layout.Root "${happyOutput}.build.json"
Assert-True 'a build report was written' (Test-Path -LiteralPath $reportPath)
if (Test-Path -LiteralPath $reportPath) {
    $report = Get-Content -Raw -Encoding UTF8 -LiteralPath $reportPath | ConvertFrom-Json
    Assert-True 'report status is static_verified' ($report.status -eq 'static_verified')
    Assert-True 'report records that runtime testing is still manual' ($report.runtime_status -eq 'manual_test_required')
    Assert-True 'report records the same input SHA-256' ($report.input_sha256 -eq $originalShaBefore)
    Assert-True 'report records the output SHA-256' (-not [string]::IsNullOrEmpty($report.output_sha256))
}

Write-Host ''
Write-Host "weapon-pack tests: $script:passed passed, $script:failed failed"
if ($script:failed -gt 0) {
    exit 1
}
