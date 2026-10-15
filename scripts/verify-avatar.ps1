param([string] $Config = 'src/configs/custom_avatars.json')
. (Join-Path $PSScriptRoot 'common.ps1')
$layout = Get-ProjectLayout
Assert-ProjectTooling $layout
$configPath = Resolve-ConfigPath $layout $Config
Compile-SwfPatcher $layout
$classpath = "$($layout.Classes);$($layout.FfdecJar);$($layout.FfdecLib)"
& $layout.Java -Xmx2g -cp $classpath SwfAvatarPatcher verify $configPath $layout.Root
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
