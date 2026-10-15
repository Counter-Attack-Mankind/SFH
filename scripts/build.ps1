param(
    [string] $Config = 'src/configs/usp_damage_test.json'
)

. (Join-Path $PSScriptRoot 'common.ps1')

$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
$loaded = Read-ModConfig $layout $Config
$mod = $loaded.Value
$output = Resolve-SafeOutput $layout $mod.output
$reportPath = [IO.Path]::ChangeExtension($output, '.build.json')
Remove-Item -LiteralPath $reportPath -Force -ErrorAction SilentlyContinue

Compile-SwfPatcher $layout

$arguments = @(
    'patch',
    $layout.Original,
    $output,
    [string]$mod.target.class,
    [string]$mod.target.method,
    [string]$mod.target.weapon,
    [string]$mod.target.field,
    [string]$mod.expected_original,
    [string]$mod.new_value
)

$started = Get-Date
try {
    $result = Invoke-SwfPatcher $layout $arguments
    $report = [ordered]@{
        status = 'static_verified'
        runtime_status = 'manual_test_required'
        mod_id = [string]$mod.mod_id
        description = [string]$mod.description
        config = $loaded.Path
        input = $layout.Original
        input_sha256 = $result.INPUT_SHA256
        output = $output
        output_sha256 = $result.OUTPUT_SHA256
        target = $mod.target
        expected_original = [int]$mod.expected_original
        new_value = [int]$mod.new_value
        abc_method_body_index = [int]$result.ABC_METHOD_BODY_INDEX
        avm2_address = $result.AVM2_ADDRESS
        built_at = (Get-Date).ToString('o')
        duration_ms = [int]((Get-Date) - $started).TotalMilliseconds
        patch_technology = 'FFDec Java API; semantic AVM2 instruction match; no AS3 recompilation'
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $reportPath
    Write-Host "Build succeeded: $output"
    Write-Host "Static verification: passed"
    Write-Host "Runtime verification: manual test required"
    Write-Host "Report: $reportPath"
} catch {
    Write-Error $_
    exit 1
}
