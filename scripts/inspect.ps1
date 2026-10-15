param(
    [string] $Config = 'src/configs/custom_weapon_pack.json'
)

. (Join-Path $PSScriptRoot 'common.ps1')

$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
$configPath = Resolve-ConfigPath $layout $Config
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

if (Test-WeaponPackConfig $configPath) {
    # Batch schema: show the addGun registration of every weapon named in the config, straight
    # from the untouched original SWF, plus the list of attributes that may be patched.
    Write-Host 'Patchable attributes:'
    Invoke-SwfPatcher $layout @('list-fields') | Out-Null

    $pack = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
    $wanted = @($pack.weapons | ForEach-Object { [string]$_.id })
    Write-Host "Original registrations for: $($wanted -join ', ')"
    $lines = @(& $layout.Java -cp "$($layout.Classes);$($layout.FfdecJar);$($layout.FfdecLib)" `
        SwfGunPatcher list-weapons $layout.Original 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "SwfGunPatcher list-weapons failed with exit code $LASTEXITCODE"
    }
    foreach ($line in $lines) {
        $text = [string]$line
        if ($text -match '^(ADDGUN_CALLS|RESOLVED_WEAPONS|RESOLVER_SELF_TEST)=') {
            Write-Host $text
            continue
        }
        foreach ($id in $wanted) {
            if ($text -like "WEAPON`t$id`t*") {
                Write-Host $text
            }
        }
    }
    Write-Host "Original SHA-256: $((Get-FileHash -Algorithm SHA256 -LiteralPath $layout.Original).Hash)"
    return
}

$loaded = Read-ModConfig $layout $Config
$mod = $loaded.Value
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
