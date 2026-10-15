Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ProjectLayout {
    $root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    [pscustomobject]@{
        Root       = $root
        Java       = Join-Path $root 'tools/Java/bin/java.exe'
        Javac      = Join-Path $root 'tools/Java/bin/javac.exe'
        FfdecJar   = Join-Path $root 'tools/FFDec/ffdec.jar'
        FfdecLib   = Join-Path $root 'tools/FFDec/lib/*'
        Classes    = Join-Path $root '.cache/java-classes'
        Patcher    = Join-Path $root 'src/utilities/SwfGunPatcher.java'
        Original   = Join-Path $root 'original/SFH_CN_Fixed.swf'
    }
}

function Assert-ProjectTooling($layout) {
    foreach ($path in @($layout.Java, $layout.Javac, $layout.FfdecJar, $layout.Patcher, $layout.Original)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Required file is missing: $path"
        }
    }
}

function Compile-SwfPatcher($layout) {
    New-Item -ItemType Directory -Force -Path $layout.Classes | Out-Null
    $classpath = "$($layout.FfdecJar);$($layout.FfdecLib)"
    & $layout.Javac -encoding UTF-8 -cp $classpath -d $layout.Classes $layout.Patcher
    if ($LASTEXITCODE -ne 0) {
        throw "SwfGunPatcher compilation failed with exit code $LASTEXITCODE"
    }
}

function Invoke-SwfPatcher($layout, [string[]] $Arguments) {
    $classpath = "$($layout.Classes);$($layout.FfdecJar);$($layout.FfdecLib)"
    $lines = @(& $layout.Java -cp $classpath SwfGunPatcher @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $lines | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        throw "SwfGunPatcher failed with exit code $exitCode"
    }
    $result = @{}
    foreach ($line in $lines) {
        $text = [string]$line
        if ($text -match '^([^=]+)=(.*)$') {
            $result[$Matches[1]] = $Matches[2]
        }
    }
    return $result
}

function Read-ModConfig($layout, [string] $ConfigPath) {
    $resolved = if ([IO.Path]::IsPathRooted($ConfigPath)) {
        (Resolve-Path -LiteralPath $ConfigPath).Path
    } else {
        (Resolve-Path -LiteralPath (Join-Path $layout.Root $ConfigPath)).Path
    }
    $config = Get-Content -Raw -Encoding UTF8 -LiteralPath $resolved | ConvertFrom-Json
    foreach ($name in @('mod_id', 'target', 'expected_original', 'new_value', 'output')) {
        if ($null -eq $config.$name) {
            throw "Config is missing '$name': $resolved"
        }
    }
    return [pscustomobject]@{ Path = $resolved; Value = $config }
}

function Resolve-SafeOutput($layout, [string] $RelativePath) {
    if ([IO.Path]::IsPathRooted($RelativePath)) {
        throw 'Config output must be a project-relative path'
    }
    $output = [IO.Path]::GetFullPath((Join-Path $layout.Root $RelativePath))
    $buildRoot = [IO.Path]::GetFullPath((Join-Path $layout.Root 'builds'))
    if (-not $output.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Output must stay inside builds/: $output"
    }
    return $output
}
