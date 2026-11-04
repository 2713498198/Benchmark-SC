[CmdletBinding()]
param(
    [string]$OutputDir = '',
    [switch]$Installer,
    [switch]$AllowExternalOutput,
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$AppVersion = '1.0.0',
    [switch]$Release,
    [ValidateSet('None', 'Required')][string]$SigningMode = 'None',
    [string]$PreparedAppImage = '',
    [ValidatePattern('^[A-Za-z][A-Za-z0-9-]{0,40}$')][string]$ApplicationName = 'CryptoBench',
    [guid]$UpgradeUuid = 'f7b2f3a6-9f8a-4e2d-8e0a-4a89b9d6c2f1'
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($ApplicationName -ne 'CryptoBench' -and (-not $PSBoundParameters.ContainsKey('UpgradeUuid') -or $UpgradeUuid -eq [guid]'f7b2f3a6-9f8a-4e2d-8e0a-4a89b9d6c2f1' -or $Release)) { throw 'Isolated test names require an explicit distinct UpgradeUuid and cannot be release builds.' }
if ($ApplicationName -eq 'CryptoBench' -and $UpgradeUuid -ne [guid]'f7b2f3a6-9f8a-4e2d-8e0a-4a89b9d6c2f1') { throw 'The production upgrade identity must remain stable.' }
. (Join-Path $PSScriptRoot 'windows-package-common.ps1')
if (-not $OutputDir) { $OutputDir = Join-Path $root 'output\desktop' }
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
if (-not $AllowExternalOutput -and -not $OutputDir.StartsWith($root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "OutputDir must stay inside the project directory: $root" }
Assert-CryptoBenchPlainPath $OutputDir
$artifactName = if ($Installer) { "$ApplicationName-$AppVersion.exe" } else { $ApplicationName }
$destination = Join-Path $OutputDir $artifactName
if (-not $Installer) { Assert-CryptoBenchReplaceableImage $destination }
if ($PreparedAppImage -and -not $Installer) { throw 'PreparedAppImage is only valid with Installer.' }
if ($PreparedAppImage) {
    $PreparedAppImage = [IO.Path]::GetFullPath($PreparedAppImage)
    Assert-CryptoBenchReplaceableImage $PreparedAppImage
    $manifest = Get-Content -LiteralPath (Join-Path $PreparedAppImage '.cryptobench-payload.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.version -ne $AppVersion) { throw 'Prepared app image version does not match AppVersion.' }
    if (-not (Test-Path -LiteralPath (Join-Path $PreparedAppImage "$ApplicationName.exe"))) { throw 'Prepared image application name does not match.' }
}
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:PATH
$originalMavenUserHome = $env:MAVEN_USER_HOME
$staging = $null
try {
    . (Join-Path $PSScriptRoot 'java-env.ps1')
    Initialize-CryptoBenchJava $root -Pinned:$Release
    $javaHome = $env:JAVA_HOME
    $jpackage = Join-Path $javaHome 'bin\jpackage.exe'
    $jlink = Join-Path $javaHome 'bin\jlink.exe'
    if (-not (Test-Path -LiteralPath $jpackage) -or -not (Test-Path -LiteralPath $jlink)) { throw 'A full JDK 21 with jpackage and jlink is required.' }
    # Read a plain .NET string: PowerShell 5 Get-Content attaches filesystem
    # metadata that ConvertTo-Json would otherwise include in public artifacts.
    $jdkRelease = [IO.File]::ReadAllText((Join-Path $javaHome 'release'))
    if ($Release) {
        $pinned = Import-Csv (Join-Path $PSScriptRoot 'jdk-distributions.tsv') -Delimiter "`t" | Where-Object { $_.os -eq 'windows' -and $_.arch -eq 'x64' }
        $expectedVersion = ($pinned.version -split '\+')[0]
        $expectedBuild = (($pinned.version -split '\+')[1] -split '\.')[0]
        $expectedRuntimePattern = '(?m)^JAVA_RUNTIME_VERSION="' + [regex]::Escape("$expectedVersion+$expectedBuild") + '(?:-LTS)?"'
        if ($jdkRelease -notmatch ('(?m)^JAVA_VERSION="' + [regex]::Escape($expectedVersion) + '"') -or $jdkRelease -notmatch $expectedRuntimePattern -or $jdkRelease -notmatch 'IMPLEMENTOR="Eclipse Adoptium"' -or $jdkRelease -notmatch 'OS_ARCH="(amd64|x86_64)"') {
            throw "Release packaging requires the pinned Temurin $expectedVersion Windows x64 JDK. Set JAVA_HOME accordingly."
        }
    }
    if ($PreparedAppImage) {
        if ($Release) {
            $preparedRuntime = [IO.File]::ReadAllText((Join-Path $PreparedAppImage 'runtime\release'))
            # jlink's release file omits vendor and architecture. Probe the actual
            # bundled JVM instead of trusting the build host or metadata alone.
            $runtimeProbe = Get-CryptoBenchJdkVersion (Join-Path $PreparedAppImage 'runtime\bin\java.exe') '-XshowSettings:properties -version'
            $runtimeVersionPattern = '(?m)^\s*java\.runtime\.version\s*=\s*' + [regex]::Escape("$expectedVersion+$expectedBuild") + '(?:-LTS)?\s*$'
            if ($runtimeProbe.ExitCode -ne 0 -or $preparedRuntime -notmatch ('(?m)^JAVA_VERSION="' + [regex]::Escape($expectedVersion) + '"') -or $runtimeProbe.Output -notmatch $runtimeVersionPattern -or $runtimeProbe.Output -notmatch '(?m)^\s*java\.vendor\s*=\s*Eclipse Adoptium\s*$' -or $runtimeProbe.Output -notmatch '(?m)^\s*os\.arch\s*=\s*(amd64|x86_64)\s*$') {
                throw "Prepared image must contain the pinned Temurin $expectedVersion Windows x64 runtime."
            }
        }
        if ($SigningMode -eq 'Required') {
            $preparedSignature = Get-AuthenticodeSignature -LiteralPath (Join-Path $PreparedAppImage "$ApplicationName.exe")
            if ($preparedSignature.Status -ne 'Valid' -or -not $preparedSignature.TimeStamperCertificate) { throw 'Prepared image must have a valid timestamped launcher signature.' }
            $preparedBuild = Get-Content -LiteralPath (Join-Path $PreparedAppImage 'support\build-info.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($preparedBuild.signing -ne 'Required') { throw 'Prepared image signing metadata does not match required signing.' }
        }
    }
    if ($Installer) {
        if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -or -not (Get-Command light.exe -ErrorAction SilentlyContinue)) {
            $wix = Get-ChildItem (Join-Path $root '.tools\wix314') -Filter candle.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
            if (-not $wix -or -not (Test-Path -LiteralPath (Join-Path $wix.Directory.FullName 'light.exe'))) { throw 'Install WiX 3.14.1 or prepare .tools\wix314.' }
            $env:PATH = $wix.Directory.FullName + ';' + $env:PATH
        }
    }
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    # The final directory is never used as jpackage's scratch area.
    $staging = Join-Path $OutputDir ('.build-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $staging | Out-Null
    $image = Join-Path $staging "image\$ApplicationName"
    if ($PreparedAppImage) {
        New-Item -ItemType Directory -Path (Split-Path $image) | Out-Null
        Copy-Item -LiteralPath $PreparedAppImage -Destination $image -Recurse
    } else {
        Push-Location $root
        try {
            & (Join-Path $root 'mvnw.cmd') '-DskipTests' 'package'
            if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
        } finally { Pop-Location }
        $jar = Join-Path $root 'target\cryptobench-1.0.0-SNAPSHOT.jar'
        if (-not (Test-Path -LiteralPath $jar)) { throw "Packaged JAR not found: $jar" }
        $input = Join-Path $staging 'input'
        New-Item -ItemType Directory -Path $input | Out-Null
        Copy-Item -LiteralPath $jar -Destination $input
        $runtime = Join-Path $staging 'runtime'
        & $jlink --module-path (Join-Path $javaHome 'jmods') --add-modules ALL-MODULE-PATH --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $runtime
        if ($LASTEXITCODE -ne 0) { throw 'jlink failed.' }
        $arguments = @('--type', 'app-image', '--name', $ApplicationName, '--input', $input,
            '--main-jar', (Split-Path $jar -Leaf), '--main-class', 'org.springframework.boot.loader.launch.PropertiesLauncher',
            '--runtime-image', $runtime, '--dest', (Split-Path $image), '--app-version', $AppVersion,
            '--vendor', 'CryptoBench', '--description', 'Cryptanalysis agent benchmarking platform',
            '--java-options', '-Dspring.profiles.active=desktop',
            '--java-options', '-Dcryptobench.desktop.home=$APPDIR/..',
            '--java-options', '-Dloader.main=com.cryptobench.desktop.DesktopLauncher')
        if ($PSBoundParameters['Verbose']) { $arguments += '--verbose' }
        & $jpackage @arguments
        if ($LASTEXITCODE -ne 0) { throw 'jpackage app-image failed.' }
        & (Join-Path $PSScriptRoot 'set-windows-launcher-encoding.ps1') -File (Join-Path $image "$ApplicationName.exe")
        $support = Join-Path $image 'support'
        New-Item -ItemType Directory -Path (Join-Path $support 'docker') -Force | Out-Null
        foreach ($name in @('agent-proxy.Dockerfile', 'agent-proxy.mjs', 'model-gateway.Dockerfile', 'model-gateway.mjs')) {
            Copy-Item -LiteralPath (Join-Path $root "docker\$name") -Destination (Join-Path $support 'docker')
        }
        foreach ($name in @('mock-agent.Dockerfile', 'mock-agent.mjs')) {
            Copy-Item -LiteralPath (Join-Path $root "tests\fixtures\$name") -Destination (Join-Path $support 'docker')
        }
        foreach ($name in @('desktop-data.ps1', 'prepare-desktop-docker.ps1')) {
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $support
        }
        Copy-Item -LiteralPath (Join-Path $root 'docs\INSTALL-WINDOWS.md') -Destination $support
        [ordered]@{ version = $AppVersion; applicationName = $ApplicationName; java = $jdkRelease; jdkBytes = (Get-ChildItem $javaHome -File -Recurse | Measure-Object Length -Sum).Sum; runtimeBytes = (Get-ChildItem $runtime -File -Recurse | Measure-Object Length -Sum).Sum; signing = $SigningMode } |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $support 'build-info.json') -Encoding UTF8
        & (Join-Path $PSScriptRoot 'sign-windows.ps1') -File (Join-Path $image "$ApplicationName.exe") -Mode $SigningMode
        Write-CryptoBenchPayloadManifest $image $AppVersion
    }
    & node (Join-Path $root 'tests\windows-package-content.mjs') $image $ApplicationName
    if ($LASTEXITCODE -ne 0) { throw 'App image payload validation failed.' }
    if ($Installer) {
        $resources = Join-Path $staging 'resources'
        New-Item -ItemType Directory -Path $resources | Out-Null
        Copy-Item -LiteralPath (Join-Path $root 'desktop\windows\CryptoBench-post-image.wsf') -Destination (Join-Path $resources "$ApplicationName-post-image.wsf")
        $arguments = @('--type', 'exe', '--name', $ApplicationName, '--app-image', $image,
            '--dest', (Join-Path $staging 'installer'), '--app-version', $AppVersion,
            '--vendor', 'CryptoBench', '--description', 'Cryptanalysis agent benchmarking platform',
            '--win-per-user-install', '--win-dir-chooser', '--win-menu', '--win-menu-group', $ApplicationName,
            '--resource-dir', $resources, '--win-shortcut', '--win-upgrade-uuid', $UpgradeUuid.ToString())
        if ($PSBoundParameters['Verbose']) { $arguments += '--verbose' }
        & $jpackage @arguments
        if ($LASTEXITCODE -ne 0) { throw 'jpackage installer failed.' }
        $installerFile = Join-Path $staging "installer\$artifactName"
        & (Join-Path $PSScriptRoot 'sign-windows.ps1') -File $installerFile -Mode $SigningMode
        Publish-CryptoBenchArtifact $installerFile $destination
    } else { Publish-CryptoBenchArtifact $image $destination -Image }
    Write-Host "Created CryptoBench $AppVersion at $destination (signing: $SigningMode)"
} finally {
    try {
        if ($staging -and (Test-Path -LiteralPath $staging)) {
            $safe = [IO.Path]::GetFullPath($staging)
            if (-not $safe.StartsWith($OutputDir.TrimEnd('\') + '\.build-', [StringComparison]::OrdinalIgnoreCase)) { throw 'Staging path escaped output directory.' }
            Assert-CryptoBenchPlainPath $safe
            Remove-Item -LiteralPath $safe -Recurse -Force
        }
    } finally {
        $env:JAVA_HOME = $originalJavaHome
        $env:PATH = $originalPath
        $env:MAVEN_USER_HOME = $originalMavenUserHome
    }
}
