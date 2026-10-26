[CmdletBinding()]
param(
    [string]$OutputDir = "",
    [switch]$Installer,
    [ValidatePattern('^\d+(\.\d+){1,3}$')]
    [string]$AppVersion = '1.0.0'
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $root 'output\desktop'
}
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
if (-not $OutputDir.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDir must stay inside the project directory: $root"
}

$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { (Get-Command java.exe -ErrorAction Stop).Source | Split-Path | Split-Path }
$jpackage = Join-Path $javaHome 'bin\jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage)) {
    throw "JDK 21 jpackage.exe was not found under $javaHome"
}

$maven = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
if ($maven -and -not (Test-Path -LiteralPath $maven)) { $maven = $null }
if (-not $maven -and $env:MAVEN_HOME) {
    $candidate = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
    if (Test-Path -LiteralPath $candidate) { $maven = $candidate }
}
if (-not $maven) {
    $bundled = Get-ChildItem (Join-Path $root '.tools') -Filter mvn.cmd -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($bundled) { $maven = $bundled.FullName }
}
if (-not $maven) {
    $extensionMaven = Get-ChildItem (Join-Path $env:USERPROFILE '.vscode\extensions') -Filter mvn.cmd -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($extensionMaven) { $maven = $extensionMaven.FullName }
}
if (-not $maven) { throw 'Maven 3.6.3 or newer is required to build the application JAR.' }

Push-Location $root
try {
    & $maven '-DskipTests' 'package'
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
} finally {
    Pop-Location
}

$jar = Join-Path $root 'target\cryptobench-1.0.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw "Packaged JAR was not found: $jar" }
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$type = if ($Installer) { 'exe' } else { 'app-image' }
if ($Installer) {
    $candle = Get-Command candle.exe -ErrorAction SilentlyContinue
    $light = Get-Command light.exe -ErrorAction SilentlyContinue
    if (-not $candle -or -not $light) {
        $bundledWix = Get-ChildItem (Join-Path $root '.tools\wix314') -Filter candle.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        $bundledLight = Get-ChildItem (Join-Path $root '.tools\wix314') -Filter light.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($bundledWix -and $bundledLight) {
            $env:Path = "$($bundledWix.Directory.FullName);$env:Path"
        } else {
            throw 'The EXE installer requires WiX Toolset (candle.exe and light.exe). Install WiX or place the official binaries under .tools\wix314.'
        }
    }
}

$arguments = @(
    '--type', $type,
    '--name', 'CryptoBench',
    '--input', (Split-Path $jar),
    '--main-jar', (Split-Path $jar -Leaf),
    # Spring Boot keeps application classes under BOOT-INF/classes. The
    # launcher on the jar root loads the desktop entry point from there.
    '--main-class', 'org.springframework.boot.loader.launch.JarLauncher',
    '--runtime-image', $javaHome,
    '--dest', $OutputDir,
    '--app-version', $AppVersion,
    '--vendor', 'CryptoBench',
    '--description', 'Cryptanalysis agent benchmarking platform',
    '--java-options', '-Dserver.address=127.0.0.1',
    '--java-options', '-Dserver.port=4173',
    '--java-options', '-Dspring.profiles.active=local',
    '--java-options', '-Dloader.main=com.cryptobench.desktop.DesktopLauncher'
)
& $jpackage @arguments
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }
Write-Host "Created CryptoBench $type in $OutputDir"
