param(
    [switch] $SkipLegacy
)

# Runs every automated suite. All of them are STATIC checks over the bytecode of the produced
# SWF; none of them can prove the game behaves correctly at runtime.

. (Join-Path $PSScriptRoot 'common.ps1')

$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
Compile-SwfPatcher $layout

$suites = @()
if (-not $SkipLegacy) {
    $suites += 'tests/test-usp-patch.ps1'
}
$suites += 'tests/test-weapon-pack.ps1'

$failed = @()
foreach ($suite in $suites) {
    $path = Join-Path $layout.Root $suite
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Write-Host "SKIP  $suite (not present)"
        continue
    }
    Write-Host ''
    Write-Host "=== $suite"
    & powershell -NoProfile -ExecutionPolicy Bypass -File $path
    if ($LASTEXITCODE -ne 0) {
        $failed += $suite
    }
}

Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host "FAILED suites: $($failed -join ', ')"
    exit 1
}
Write-Host 'All automated suites passed.'
Write-Host 'Runtime verification in the actual game is still required.'
