[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InitialInstaller,
    [string]$UpgradeInstaller = '',
    [switch]$LegacyInitial,
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$InitialVersion = '1.0.0',
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$UpgradeVersion = '1.0.1',
    [string]$InstallRoot = '',
    [string]$LogDirectory = "$env:TEMP\\cryptobench-installer-test",
    [ValidatePattern('^[A-Za-z][A-Za-z0-9-]{0,40}$')][string]$ApplicationName = 'CryptoBench'
)

$ErrorActionPreference = 'Stop'
$LogDirectory = [IO.Path]::GetFullPath($LogDirectory)
$InitialInstaller = [IO.Path]::GetFullPath($InitialInstaller)
if ($UpgradeInstaller) { $UpgradeInstaller = [IO.Path]::GetFullPath($UpgradeInstaller) }
if (-not (Test-Path -LiteralPath $InitialInstaller)) { throw "Initial installer was not found: $InitialInstaller" }
if ($UpgradeInstaller -and -not (Test-Path -LiteralPath $UpgradeInstaller)) { throw "Upgrade installer was not found: $UpgradeInstaller" }
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
if ($InstallRoot) {
    $InstallRoot = [IO.Path]::GetFullPath($InstallRoot)
    New-Item -ItemType Directory -Force -Path (Split-Path $InstallRoot) | Out-Null
    if (Test-Path -LiteralPath $InstallRoot) {
        throw "Installer test requires a new, isolated directory: $InstallRoot"
    }
}
elseif (Test-Path -LiteralPath (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) $ApplicationName)) {
    throw 'The default application directory already exists; this test will not touch existing user data.'
}

$uninstallRoots = @(
    'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'
)

function Get-CryptoBenchEntry {
    foreach ($root in $uninstallRoots) {
        Get-ItemProperty -Path $root -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -eq $ApplicationName }
    }
}

function Wait-CryptoBenchEntry([int]$timeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        $entry = @(Get-CryptoBenchEntry) | Select-Object -First 1
        if ($entry) { return $entry }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw 'CryptoBench was not registered in the Windows uninstall registry.'
}

function InstallerArguments([string]$log, [string]$directory) {
    if ($log -match '["\r\n]' -or $directory -match '["\r\n]') { throw 'Invalid installer argument path.' }
    # jpackage's EXE wrapper re-quotes each argv containing spaces, turning
    # INSTALLDIR="a b" into "INSTALLDIR=a b", which MSI rejects. Preserve the
    # literal MSI quotes in one log/path tail; Executor leaves this quoted tail
    # intact. Both values are validated test-owned paths, never shell commands.
    $tail = '\"' + $log + '\"'
    if ($directory) { $tail += ' INSTALLDIR=\"' + $directory + '\"' }
    return @('/quiet', '/norestart', '/l*v', ('"' + $tail + '"'))
}

function Invoke-Installer([string]$path, [string]$logName) {
    $log = Join-Path $LogDirectory $logName
    $arguments = InstallerArguments $log $InstallRoot
    $process = Start-Process -FilePath $path -ArgumentList $arguments -PassThru -WindowStyle Hidden
    if (-not $process.WaitForExit(120000)) {
        # Stop only this test's own client process tree, never the MSI service.
        & taskkill.exe /PID $process.Id /T /F | Out-Null
        throw "Installer timed out after 120 seconds. Inspect Windows Installer state and $log"
    }
    if ($process.ExitCode -ne 0) { throw "Installer failed with exit code $($process.ExitCode): $path" }
}

function Assert-InstalledExecutable($entry) {
    $location = [string]$entry.InstallLocation
    if ([string]::IsNullOrWhiteSpace($location)) {
        $icon = [string]$entry.DisplayIcon
        if ($icon) { $location = Split-Path ($icon.Trim('"').Split(',')[0]) }
    }
    $executable = if ($location) { Join-Path $location "$ApplicationName.exe" } else { '' }
    if (-not $executable -or -not (Test-Path -LiteralPath $executable)) {
        throw "Installed CryptoBench.exe was not found. Registry install location: $location"
    }
    if ($InstallRoot -and ([IO.Path]::GetFullPath((Split-Path $executable)) -ne $InstallRoot)) {
        throw "Installer did not honor the selected directory. Expected $InstallRoot, found $location"
    }
    return $executable
}

function ShortcutPaths {
    @(
        (Join-Path ([Environment]::GetFolderPath('ApplicationData')) "Microsoft\Windows\Start Menu\Programs\$ApplicationName\$ApplicationName.lnk"),
        (Join-Path ([Environment]::GetFolderPath('Desktop')) "$ApplicationName.lnk")
    )
}

function Assert-Shortcuts([string]$executable) {
    $shell = New-Object -ComObject WScript.Shell
    foreach ($path in (ShortcutPaths)) {
        if (-not (Test-Path -LiteralPath $path)) { throw "Expected CryptoBench shortcut was not created: $path" }
        $target = [IO.Path]::GetFullPath($shell.CreateShortcut($path).TargetPath)
        if ($target -ne [IO.Path]::GetFullPath($executable)) {
            throw "CryptoBench shortcut points to $target instead of $executable"
        }
    }
}

function Assert-ShortcutsRemoved {
    foreach ($path in (ShortcutPaths)) {
        if (Test-Path -LiteralPath $path) { throw "CryptoBench shortcut remains after uninstall: $path" }
    }
}

function Start-AndCheckHealth([string]$executable, [switch]$ExpectExisting, [switch]$AllowLegacy) {
    $configuration = Get-Content -LiteralPath (Join-Path (Split-Path $executable) "app\$ApplicationName.cfg") -Raw
    if ($AllowLegacy -and $configuration -notmatch 'spring.profiles.active=desktop') {
        & node (Join-Path $PSScriptRoot 'windows-legacy-app.mjs') $executable
        if ($LASTEXITCODE -ne 0) { throw 'Historical release fixture failed.' }
        return
    }
    & node (Join-Path $PSScriptRoot 'windows-package-content.mjs') (Split-Path $executable) $ApplicationName
    if ($LASTEXITCODE -ne 0) { throw 'Installed payload verification failed.' }
    $extra = @()
    if ($ExpectExisting) { $extra += '--expect-persisted-user' }
    & node (Join-Path $PSScriptRoot 'windows-app-image.mjs') $executable --in-place --shortcut (ShortcutPaths)[0] @extra
    if ($LASTEXITCODE -ne 0) { throw 'Installed desktop entry/readiness check failed.' }
}

function Assert-MaintenanceBlocked([string]$executable, [string]$installer, [string]$productCode) {
    $configuration = Get-Content -LiteralPath (Join-Path (Split-Path $executable) "app\$ApplicationName.cfg") -Raw
    $desktop = $configuration -match 'spring.profiles.active=desktop'
    if (-not $desktop -and -not $installer) { return }
    $previousOptions = $env:JAVA_TOOL_OPTIONS
    $previousEntry = @(Get-CryptoBenchEntry) | Select-Object -First 1
    $previousExecutableHash = (Get-FileHash -LiteralPath $executable).Hash
    $process = $null
    try {
        $env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=true'
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $port = $listener.LocalEndpoint.Port
        $listener.Stop()
        $applicationRoot = Split-Path $executable
        $applicationArguments = @("--server.port=$port", '--server.address=127.0.0.1', '--benchmark.docker.enabled=false', '--benchmark.gateway.enabled=false')
        if (-not $desktop) {
            # Historical binaries have no lock/IPC. Their running process must
            # still block the NEW installer via its executable-path check.
            $databasePath = (Join-Path $applicationRoot 'var\data\cryptobench').Replace('\', '/')
            $applicationArguments += '"--spring.datasource.url=jdbc:h2:file:' + $databasePath + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0"'
            $applicationArguments += '"--logging.file.name=' + (Join-Path $applicationRoot 'var\logs\maintenance-test.log') + '"'
            $applicationArguments += '--benchmark.demo-enabled=false'
        }
        $process = Start-Process -FilePath $executable -ArgumentList $applicationArguments -WorkingDirectory $applicationRoot -PassThru -WindowStyle Hidden
        $ready = $false
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
            try { $ready = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri "http://127.0.0.1:$port/actuator/health").StatusCode -eq 200 } catch { }
            if ($ready) { break }
            Start-Sleep -Milliseconds 200
        }
        if (-not $ready) { throw 'Could not start application for the running-maintenance test.' }
        $log = Join-Path $LogDirectory $(if ($installer) { 'running-upgrade.log' } else { 'running-uninstall.log' })
        if ($installer) {
            $arguments = InstallerArguments $log (Split-Path $executable)
            $maintenance = Start-Process -FilePath $installer -ArgumentList $arguments -PassThru -WindowStyle Hidden
        } else {
            $maintenance = Start-Process -FilePath "$env:WINDIR\System32\msiexec.exe" -ArgumentList @('/x', $productCode, '/qn', '/norestart', '/l*v', ('"' + $log + '"')) -PassThru -WindowStyle Hidden
        }
        if (-not $maintenance.WaitForExit(120000)) { & taskkill.exe /PID $maintenance.Id /T /F | Out-Null; throw 'Running-maintenance check timed out.' }
        if ($maintenance.ExitCode -eq 0 -or $maintenance.ExitCode -eq 3010) { throw 'Installer allowed maintenance while CryptoBench was running.' }
        $process.Refresh()
        if ($process.HasExited) { throw 'Installer forcibly closed the running application.' }
        $retainedEntry = @(Get-CryptoBenchEntry) | Select-Object -First 1
        if (-not $retainedEntry -or $retainedEntry.PSChildName -ne $previousEntry.PSChildName -or $retainedEntry.DisplayVersion -ne $previousEntry.DisplayVersion) { throw 'Rejected maintenance changed the installed product.' }
        if (-not (Test-Path -LiteralPath $executable) -or (Get-FileHash -LiteralPath $executable).Hash -ne $previousExecutableHash) { throw 'Rejected maintenance removed or replaced the running executable.' }
        if (-not ((Get-Content -LiteralPath $log -Raw) -match 'CryptoBenchRequireStopped|CryptoBench is running')) { throw 'Maintenance failed for an unrelated reason; the running-process guard was not verified.' }
    } finally {
        if ($process -and -not $process.HasExited) {
            if ($desktop) {
                $stop = Start-Process -FilePath $executable -ArgumentList '--desktop-command=stop' -Wait -PassThru -WindowStyle Hidden
                if (-not $process.WaitForExit(60000)) { $process.Kill(); throw 'Application failed to stop after maintenance check.' }
                if ($stop.ExitCode -ne 0) { throw 'Graceful control command failed after maintenance check.' }
            } else {
                # Only the old test process lacks an exit API; its committed H2
                # writes are synchronous and no measurement is running here.
                $process.Kill()
                if (-not $process.WaitForExit(10000)) { throw 'Historical maintenance fixture did not terminate.' }
            }
        }
        $env:JAVA_TOOL_OPTIONS = $previousOptions
    }
}

if (Get-CryptoBenchEntry) { throw 'An existing CryptoBench installation must not be replaced by this test.' }
Invoke-Installer $InitialInstaller 'install.log'
$installed = Wait-CryptoBenchEntry
if ([string]$installed.DisplayVersion -ne $InitialVersion) {
    throw "Initial installer version mismatch: expected $InitialVersion, found $($installed.DisplayVersion)"
}
$executable = Assert-InstalledExecutable $installed
Assert-Shortcuts $executable
Start-AndCheckHealth $executable -AllowLegacy:$LegacyInitial
$dataRoot = Join-Path (Split-Path $executable) 'var\data'
New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null
$sentinel = Join-Path $dataRoot 'upgrade-sentinel.txt'
Set-Content -LiteralPath $sentinel -Value 'CryptoBench installer lifecycle test' -Encoding UTF8
Write-Host "Installed CryptoBench $InitialVersion at $executable"

if ($UpgradeInstaller) {
Assert-MaintenanceBlocked $executable $UpgradeInstaller ''
Invoke-Installer $UpgradeInstaller 'upgrade.log'
$upgraded = Wait-CryptoBenchEntry
if ([string]$upgraded.DisplayVersion -ne $UpgradeVersion) {
    throw "Upgrade installer version mismatch: expected $UpgradeVersion, found $($upgraded.DisplayVersion)"
}
Assert-InstalledExecutable $upgraded | Out-Null
Assert-Shortcuts (Assert-InstalledExecutable $upgraded)
if (-not (Test-Path -LiteralPath $sentinel)) { throw 'Upgrade removed the local data sentinel.' }
Start-AndCheckHealth (Assert-InstalledExecutable $upgraded) -ExpectExisting
Write-Host "Upgraded CryptoBench to $UpgradeVersion"
} else { $upgraded = $installed }

$productCode = $upgraded.PSChildName
Assert-MaintenanceBlocked (Assert-InstalledExecutable $upgraded) '' $productCode
$persistentHashes = @{}
foreach ($relative in @('var\data\cryptobench.mv.db', 'var\secrets\mail.key', 'var\config\desktop.properties')) {
    $persistentFile = Join-Path (Split-Path $executable) $relative
    if (Test-Path -LiteralPath $persistentFile) { $persistentHashes[$relative] = (Get-FileHash -LiteralPath $persistentFile).Hash }
}
$uninstallLog = Join-Path $LogDirectory 'uninstall.log'
$uninstall = Start-Process -FilePath "$env:WINDIR\System32\msiexec.exe" `
    -ArgumentList @('/x', $productCode, '/qn', '/norestart', '/l*v', ('"' + $uninstallLog + '"')) -PassThru -WindowStyle Hidden
if (-not $uninstall.WaitForExit(120000)) { $uninstall.Kill(); throw 'Test uninstall timed out; retained the test installation for diagnosis.' }
if ($uninstall.ExitCode -ne 0) { throw "Uninstall failed with exit code $($uninstall.ExitCode)." }
Start-Sleep -Seconds 2
if (Get-CryptoBenchEntry) { throw 'CryptoBench is still registered after uninstall.' }
if (Test-Path -LiteralPath $executable) { throw "Installed executable remains after uninstall: $executable" }
Assert-ShortcutsRemoved
if (-not (Test-Path -LiteralPath $sentinel)) { throw 'Uninstall removed the local data sentinel.' }
foreach ($relative in $persistentHashes.Keys) {
    $persistentFile = Join-Path (Split-Path $executable) $relative
    if (-not (Test-Path -LiteralPath $persistentFile) -or (Get-FileHash -LiteralPath $persistentFile).Hash -ne $persistentHashes[$relative]) { throw "Uninstall changed persistent user data: $relative" }
}
$actualRoot = Split-Path $executable
if (Test-Path -LiteralPath $actualRoot) {
    $cleanupRoot = [IO.Path]::GetFullPath($actualRoot)
    if ($cleanupRoot -eq [IO.Path]::GetPathRoot($cleanupRoot) -or -not (Test-Path -LiteralPath $sentinel)) {
        throw "Refusing to remove an unverified test installation: $cleanupRoot"
    }
    Remove-Item -LiteralPath $cleanupRoot -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host 'PASS Windows installer lifecycle: install, optional upgrade, running-maintenance guard, uninstall'
