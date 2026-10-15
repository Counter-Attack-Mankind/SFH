param(
    [string] $Config = 'src/configs/custom_weapon_pack.json'
)

. (Join-Path $PSScriptRoot 'common.ps1')

# Dry run: parses and validates the configuration, resolves every target weapon and attribute
# against the untouched original SWF, and confirms every declared original value - without
# writing any file. Use this before Build MOD to catch a bad config cheaply.
$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
$configPath = Resolve-ConfigPath $layout $Config
Compile-SwfPatcher $layout

if (-not (Test-WeaponPackConfig $configPath)) {
    Write-Host "Config uses the v0.1 single-patch schema: $configPath"
    $loaded = Read-ModConfig $layout $Config
    Write-Host "mod_id           : $($loaded.Value.mod_id)"
    Write-Host "target weapon    : $($loaded.Value.target.weapon)"
    Write-Host "target field     : $($loaded.Value.target.field)"
    Write-Host "expected original: $($loaded.Value.expected_original)"
    Write-Host "new value        : $($loaded.Value.new_value)"
    Write-Host "output           : $($loaded.Value.output)"
    $target = Resolve-SafeOutput $layout $loaded.Value.output
    Write-Host "resolved output  : $target"
    $result = Invoke-SwfPatcher $layout @(
        'verify',
        $layout.Original,
        [string]$loaded.Value.target.class,
        [string]$loaded.Value.target.method,
        [string]$loaded.Value.target.weapon,
        [string]$loaded.Value.target.field,
        [string]$loaded.Value.expected_original
    )
    Write-Host "original value in SWF: $($result.VALUE)"
    Write-Host 'CONFIG STATUS=OK'
    return
}

$pack = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
$output = Resolve-SafeOutput $layout $pack.output

$result = Invoke-SwfPatcher $layout @('validate-config', $configPath, $layout.Root)
Write-Host "mod_id        : $($pack.mod_id)"
Write-Host "weapons       : $(@($pack.weapons).Count)"
Write-Host "changes       : $($result.CHANGES)"
Write-Host "resolved output: $output"
Write-Host 'CONFIG STATUS=OK'
