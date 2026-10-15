param(
    [string] $Config = 'src/configs/usp_damage_test.json',
    [string] $Input
)

. (Join-Path $PSScriptRoot 'common.ps1')

$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
$configPath = Resolve-ConfigPath $layout $Config
Compile-SwfPatcher $layout

if (Test-WeaponPackConfig $configPath) {
    # Batch schema. Without -Input the built SWF named in the config is verified; the Java tool
    # additionally re-resolves the untouched original and proves that only the configured
    # arguments differ from it.
    $override = if ($Input) { (Resolve-Path -LiteralPath $Input).Path } else { $null }
    if ($override) {
        $result = Invoke-SwfPatcher $layout @('verify-config', $configPath, $layout.Root, $override)
    } else {
        $result = Invoke-SwfPatcher $layout @('verify-config', $configPath, $layout.Root)
    }
    Write-Host "Changes verified: $($result.CHANGES_VERIFIED)"
    Write-Host "Weapons resolved: $($result.WEAPONS_RESOLVED)"
    Write-Host "Isolation check: $($result.ISOLATION_CHECK)"
    Write-Host "Original file: $($result.ORIGINAL_UNMODIFIED)"
    Write-Host "Target SHA-256: $($result.TARGET_SHA256)"
    Write-Host 'Runtime behavior is not asserted by this static verifier.'
    return
}

# v0.1 single-patch schema.
$loaded = Read-ModConfig $layout $Config
$mod = $loaded.Value
$target = if ($Input) { (Resolve-Path -LiteralPath $Input).Path } else { Resolve-SafeOutput $layout $mod.output }
if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
    throw "SWF to verify does not exist: $target"
}

$result = Invoke-SwfPatcher $layout @(
    'verify',
    $target,
    [string]$mod.target.class,
    [string]$mod.target.method,
    [string]$mod.target.weapon,
    [string]$mod.target.field,
    [string]$mod.new_value
)

Write-Host "Verified bytecode value: $($result.VALUE)"
Write-Host "SHA-256: $($result.INPUT_SHA256)"
Write-Host 'Runtime behavior is not asserted by this static verifier.'
