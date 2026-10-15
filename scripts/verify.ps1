param(
    [string] $Config = 'src/configs/usp_damage_test.json',
    [string] $Input
)

. (Join-Path $PSScriptRoot 'common.ps1')

$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
$loaded = Read-ModConfig $layout $Config
$mod = $loaded.Value
$target = if ($Input) { (Resolve-Path -LiteralPath $Input).Path } else { Resolve-SafeOutput $layout $mod.output }
if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
    throw "SWF to verify does not exist: $target"
}

Compile-SwfPatcher $layout
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
