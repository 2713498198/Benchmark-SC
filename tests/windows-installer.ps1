[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InitialInstaller,
    [Parameter(Mandatory = $true)]
    [string]$UpgradeInstaller,
    [string]$LogDirectory = "$env:TEMP\\cryptobench-installer-test"
)

$ErrorActionPreference = 'Stop'
$InitialInstaller = [IO.Path]::GetFullPath($InitialInstaller)
$UpgradeInstaller = [IO.Path]::GetFullPath($UpgradeInstaller)
if (-not (Test-Path -LiteralPath $InitialInstaller)) { throw "Initial installer was not found: $InitialInstaller" }
if (-not (Test-Path -LiteralPath $UpgradeInstaller)) { throw "Upgrade installer was not found: $UpgradeInstaller" }
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null

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
    $process = Start-Process -FilePath $path -ArgumentList @('/quiet', '/norestart', '/log', $log) -Wait -PassThru -WindowStyle Hidden
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
    return $executable
}

Invoke-Installer $InitialInstaller 'install.log'
$installed = Wait-CryptoBenchEntry
if ([string]$installed.DisplayVersion -ne '1.0.0') {
    throw "Initial installer version mismatch: $($installed.DisplayVersion)"
}
$executable = Assert-InstalledExecutable $installed
Write-Host "Installed CryptoBench 1.0.0 at $executable"

Invoke-Installer $UpgradeInstaller 'upgrade.log'
$upgraded = Wait-CryptoBenchEntry
if ([string]$upgraded.DisplayVersion -ne '1.0.1') {
    throw "Upgrade installer version mismatch: $($upgraded.DisplayVersion)"
}
Assert-InstalledExecutable $upgraded | Out-Null
Write-Host 'Upgraded CryptoBench to 1.0.1'

$productCode = $upgraded.PSChildName
$uninstallLog = Join-Path $LogDirectory 'uninstall.log'
$uninstall = Start-Process -FilePath "$env:WINDIR\System32\msiexec.exe" `
    -ArgumentList @('/x', $productCode, '/qn', '/norestart', '/l*v', $uninstallLog) -Wait -PassThru -WindowStyle Hidden
if ($uninstall.ExitCode -ne 0) { throw "Uninstall failed with exit code $($uninstall.ExitCode)." }
Start-Sleep -Seconds 2
if (Get-CryptoBenchEntry) { throw 'CryptoBench is still registered after uninstall.' }
if (Test-Path -LiteralPath $executable) { throw "Installed executable remains after uninstall: $executable" }
Write-Host 'PASS Windows installer lifecycle: install, upgrade, uninstall'
