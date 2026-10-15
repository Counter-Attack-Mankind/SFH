param(
    [string] $Config = 'src/configs/usp_damage_test.json'
)

. (Join-Path $PSScriptRoot 'common.ps1')

$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
$loaded = Read-ModConfig $layout $Config
$mod = $loaded.Value
Compile-SwfPatcher $layout

Write-Host "Project root: $($layout.Root)"
Write-Host "Original SWF: $($layout.Original)"
Write-Host "ActionScript files: $((Get-ChildItem -LiteralPath (Join-Path $layout.Root 'decompiled/scripts') -Recurse -File -Filter '*.as').Count)"
Write-Host 'Resource counts:'
Get-ChildItem -LiteralPath (Join-Path $layout.Root 'decompiled') -Directory |
    ForEach-Object {
        [pscustomobject]@{
            Category = $_.Name
            Files = @(Get-ChildItem -LiteralPath $_.FullName -Recurse -File -ErrorAction SilentlyContinue).Count
        }
    } | Sort-Object Category | Format-Table -AutoSize

$result = Invoke-SwfPatcher $layout @(
    'verify',
    $layout.Original,
    [string]$mod.target.class,
    [string]$mod.target.method,
    [string]$mod.target.weapon,
    [string]$mod.target.field,
    [string]$mod.expected_original
)
Write-Host "Original target value: $($result.VALUE)"
Write-Host "Original SHA-256: $($result.INPUT_SHA256)"
