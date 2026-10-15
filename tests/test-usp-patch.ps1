. (Join-Path $PSScriptRoot '../scripts/common.ps1')

$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
Compile-SwfPatcher $layout

$testRoot = Join-Path $layout.Root '.cache/tests'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$first = Join-Path $testRoot 'usp100-first.swf'
$second = Join-Path $testRoot 'usp100-second.swf'
$shouldNotExist = Join-Path $testRoot 'wrong-expected.swf'
foreach ($path in @($first, $second, $shouldNotExist)) {
    Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
}

$originalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $layout.Original).Hash
$common = @('Stats_Guns', 'Init', 'USP', 'damage')

$verifyOriginalArgs = @('verify', $layout.Original) + $common + @('15')
$patchFirstArgs = @('patch', $layout.Original, $first) + $common + @('15', '100')
$patchSecondArgs = @('patch', $layout.Original, $second) + $common + @('15', '100')
$verifyFirstArgs = @('verify', $first) + $common + @('100')
Invoke-SwfPatcher $layout $verifyOriginalArgs | Out-Null
Invoke-SwfPatcher $layout $patchFirstArgs | Out-Null
Invoke-SwfPatcher $layout $patchSecondArgs | Out-Null
Invoke-SwfPatcher $layout $verifyFirstArgs | Out-Null

$firstHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $first).Hash
$secondHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $second).Hash
if ($firstHash -ne $secondHash) {
    throw "Repeated builds are not deterministic: $firstHash != $secondHash"
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $layout.Original).Hash -ne $originalHash) {
    throw 'Original SWF was modified'
}

$classpath = "$($layout.Classes);$($layout.FfdecJar);$($layout.FfdecLib)"
$savedErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $layout.Java -cp $classpath SwfGunPatcher patch $layout.Original $shouldNotExist @common 14 100 2>$null | Out-Null
$ErrorActionPreference = $savedErrorAction
if ($LASTEXITCODE -eq 0) {
    throw 'Wrong expected value was incorrectly accepted'
}
if (Test-Path -LiteralPath $shouldNotExist) {
    throw 'Failed patch left an output artifact'
}

Write-Host 'PASS: original value verified as 15'
Write-Host 'PASS: patched value verified as 100'
Write-Host "PASS: repeated build SHA-256 is $firstHash"
Write-Host 'PASS: expected-value mismatch failed without an output artifact'
