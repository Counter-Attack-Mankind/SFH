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
        Sources    = Join-Path $root 'src/utilities'
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
    $sources = @(Get-ChildItem -LiteralPath $layout.Sources -Filter '*.java' | ForEach-Object { $_.FullName })
    if ($sources.Count -eq 0) {
        throw "No Java sources found in $($layout.Sources)"
    }
    & $layout.Javac -encoding UTF-8 -cp $classpath -d $layout.Classes @sources
    if ($LASTEXITCODE -ne 0) {
        throw "SwfGunPatcher compilation failed with exit code $LASTEXITCODE"
    }
}

function Invoke-SwfPatcher($layout, [string[]] $Arguments) {
    $classpath = "$($layout.Classes);$($layout.FfdecJar);$($layout.FfdecLib)"
    # The Java tool reports failures on stderr; without this, PowerShell turns that into a
    # terminating error and the actual ERROR= message is lost.
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $layout.Java -Xmx2g -cp $classpath SwfGunPatcher @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    $lines | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        $reason = ($lines | Where-Object { [string]$_ -match '^ERROR=' } | Select-Object -First 1)
        if ($reason) {
            throw "SwfGunPatcher failed: $reason"
        }
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

function Resolve-ConfigPath($layout, [string] $ConfigPath) {
    if ([IO.Path]::IsPathRooted($ConfigPath)) {
        return (Resolve-Path -LiteralPath $ConfigPath).Path
    }
    return (Resolve-Path -LiteralPath (Join-Path $layout.Root $ConfigPath)).Path
}

# The v0.2 batch schema is identified by a 'weapons' array; everything else uses the
# v0.1 single-patch schema. Both are still supported so the v0.1 regression keeps working.
function Test-WeaponPackConfig([string] $ConfigPath) {
    $config = Get-Content -Raw -Encoding UTF8 -LiteralPath $ConfigPath | ConvertFrom-Json
    return ($null -ne $config.PSObject.Properties['weapons'])
}

function Read-ModConfig($layout, [string] $ConfigPath) {
    $resolved = Resolve-ConfigPath $layout $ConfigPath
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
