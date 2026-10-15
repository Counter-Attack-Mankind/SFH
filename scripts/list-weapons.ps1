param(
    [string] $Filter,
    [switch] $Fields,
    [string] $Swf
)

. (Join-Path $PSScriptRoot 'common.ps1')

# Read-only: dumps every addGun registration the resolver found in the original SWF, so the real
# weapon ids and original attribute values can be read straight from the game data instead of
# being guessed. Nothing is written.
$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
Compile-SwfPatcher $layout

$targetSwf = if ($Swf) { (Resolve-Path -LiteralPath $Swf).Path } else { $layout.Original }

if ($Fields) {
    Invoke-SwfPatcher $layout @('list-fields') | Out-Null
    Write-Host ''
}

$lines = @(& $layout.Java -cp "$($layout.Classes);$($layout.FfdecJar);$($layout.FfdecLib)" SwfGunPatcher list-weapons $targetSwf 2>&1)
if ($LASTEXITCODE -ne 0) {
    $lines | ForEach-Object { Write-Host $_ }
    throw "SwfGunPatcher list-weapons failed with exit code $LASTEXITCODE"
}

foreach ($line in $lines) {
    $text = [string]$line
    if ($Filter -and $text -notmatch $Filter) {
        continue
    }
    Write-Host $text
}
