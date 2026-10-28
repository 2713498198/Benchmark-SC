[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InitialInstaller,
    [Parameter(Mandatory = $true)]
    [string]$UpgradeInstaller,
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$InitialVersion = '1.0.0',
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$UpgradeVersion = '1.0.1',
    [string]$InstallRoot = '',
    [string]$LogDirectory = "$env:TEMP\\cryptobench-installer-test"
)

$ErrorActionPreference = 'Stop'
$InitialInstaller = [IO.Path]::GetFullPath($InitialInstaller)
$UpgradeInstaller = [IO.Path]::GetFullPath($UpgradeInstaller)
if (-not (Test-Path -LiteralPath $InitialInstaller)) { throw "Initial installer was not found: $InitialInstaller" }
if (-not (Test-Path -LiteralPath $UpgradeInstaller)) { throw "Upgrade installer was not found: $UpgradeInstaller" }
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
if ($InstallRoot) {
    $InstallRoot = [IO.Path]::GetFullPath($InstallRoot)
    New-Item -ItemType Directory -Force -Path (Split-Path $InstallRoot) | Out-Null
    if (Test-Path -LiteralPath $InstallRoot) {
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force
    }
}

$uninstallRoots = @(
    'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'
)

function Get-CryptoBenchEntry {
    foreach ($root in $uninstallRoots) {
        Get-ItemProperty -Path $root -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -like 'CryptoBench*' }
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

function Invoke-Installer([string]$path, [string]$logName) {
    $log = Join-Path $LogDirectory $logName
    $arguments = @('/quiet', '/norestart', '/log', $log)
    if ($InstallRoot) { $arguments += "/D=`"$InstallRoot`"" }
    $process = Start-Process -FilePath $path -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden
    if ($process.ExitCode -ne 0) { throw "Installer failed with exit code $($process.ExitCode): $path" }
}

function Assert-InstalledExecutable($entry) {
    $location = [string]$entry.InstallLocation
    if ([string]::IsNullOrWhiteSpace($location)) {
        $icon = [string]$entry.DisplayIcon
        if ($icon) { $location = Split-Path ($icon.Trim('"').Split(',')[0]) }
    }
    $executable = if ($location) { Join-Path $location 'CryptoBench.exe' } else { '' }
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
        (Join-Path ([Environment]::GetFolderPath('ApplicationData')) 'Microsoft\Windows\Start Menu\Programs\CryptoBench\CryptoBench.lnk'),
        (Join-Path ([Environment]::GetFolderPath('Desktop')) 'CryptoBench.lnk')
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

function Start-AndCheckHealth([string]$executable) {
    $process = Start-Process -FilePath $executable -WorkingDirectory (Split-Path $executable) -PassThru -WindowStyle Hidden
    try {
        $deadline = (Get-Date).AddSeconds(60)
        $healthy = $false
        do {
            try {
                $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:4173/actuator/health' -TimeoutSec 3
                if ($response.StatusCode -eq 200) { $healthy = $true; break }
            } catch { }
            Start-Sleep -Seconds 1
        } while ((Get-Date) -lt $deadline)
        if (-not $healthy) { throw 'Installed CryptoBench did not become healthy on port 4173.' }
    } finally {
        if ($process -and -not $process.HasExited) {
            & taskkill.exe /PID $process.Id /T /F | Out-Null
            Start-Sleep -Seconds 1
        }
    }
}

Invoke-Installer $InitialInstaller 'install.log'
$installed = Wait-CryptoBenchEntry
if ([string]$installed.DisplayVersion -ne $InitialVersion) {
    throw "Initial installer version mismatch: expected $InitialVersion, found $($installed.DisplayVersion)"
}
$executable = Assert-InstalledExecutable $installed
Assert-Shortcuts $executable
Start-AndCheckHealth $executable
$dataRoot = Join-Path (Split-Path $executable) 'var\data'
New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null
$sentinel = Join-Path $dataRoot 'upgrade-sentinel.txt'
Set-Content -LiteralPath $sentinel -Value 'CryptoBench installer lifecycle test' -Encoding UTF8
Write-Host "Installed CryptoBench $InitialVersion at $executable"

Invoke-Installer $UpgradeInstaller 'upgrade.log'
$upgraded = Wait-CryptoBenchEntry
if ([string]$upgraded.DisplayVersion -ne $UpgradeVersion) {
    throw "Upgrade installer version mismatch: expected $UpgradeVersion, found $($upgraded.DisplayVersion)"
}
Assert-InstalledExecutable $upgraded | Out-Null
Assert-Shortcuts (Assert-InstalledExecutable $upgraded)
if (-not (Test-Path -LiteralPath $sentinel)) { throw 'Upgrade removed the local data sentinel.' }
Write-Host "Upgraded CryptoBench to $UpgradeVersion"

$productCode = $upgraded.PSChildName
$uninstallLog = Join-Path $LogDirectory 'uninstall.log'
$uninstall = Start-Process -FilePath "$env:WINDIR\System32\msiexec.exe" `
    -ArgumentList @('/x', $productCode, '/qn', '/norestart', '/l*v', $uninstallLog) -Wait -PassThru -WindowStyle Hidden
if ($uninstall.ExitCode -ne 0) { throw "Uninstall failed with exit code $($uninstall.ExitCode)." }
Start-Sleep -Seconds 2
if (Get-CryptoBenchEntry) { throw 'CryptoBench is still registered after uninstall.' }
if (Test-Path -LiteralPath $executable) { throw "Installed executable remains after uninstall: $executable" }
Assert-ShortcutsRemoved
if (-not (Test-Path -LiteralPath $sentinel)) { throw 'Uninstall removed the local data sentinel.' }
if ($InstallRoot -and (Test-Path -LiteralPath $InstallRoot)) {
    Remove-Item -LiteralPath $InstallRoot -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host 'PASS Windows installer lifecycle: install, upgrade, uninstall'
