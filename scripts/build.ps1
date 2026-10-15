param(
    [string] $Config = 'src/configs/usp_damage_test.json'
)

. (Join-Path $PSScriptRoot 'common.ps1')

$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
$configPath = Resolve-ConfigPath $layout $Config
Compile-SwfPatcher $layout

if (Test-WeaponPackConfig $configPath) {
    # v0.2 batch schema: one JSON file, many weapons and many attributes. The Java tool owns the
    # whole transaction (resolve -> validate -> rewrite -> re-parse -> verify -> publish) and also
    # writes the build report, so this script only runs it and surfaces the outcome.
    $pack = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
    $output = Resolve-SafeOutput $layout $pack.output
    $reportPath = "${output}.build.json"

    try {
        $result = Invoke-SwfPatcher $layout @('patch-config', $configPath, $layout.Root)
        Write-Host "Build succeeded: $output"
        Write-Host "Weapons resolved: $($result.WEAPONS_RESOLVED)"
        Write-Host "Arguments compared: $($result.ARGUMENTS_COMPARED)"
        Write-Host "Changes applied: $($result.CHANGES)"
        Write-Host "Static verification: passed (every target re-parsed from the written SWF)"
        Write-Host "Runtime verification: manual test required"
        Write-Host "Report: $reportPath"
    } catch {
        Write-Error $_
        exit 1
    }
    return
}

# v0.1 single-patch schema, kept unchanged so the historical USP builds and their tests still work.
$loaded = Read-ModConfig $layout $Config
$mod = $loaded.Value
$output = Resolve-SafeOutput $layout $mod.output
$reportPath = [IO.Path]::ChangeExtension($output, '.build.json')
Remove-Item -LiteralPath $reportPath -Force -ErrorAction SilentlyContinue

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
