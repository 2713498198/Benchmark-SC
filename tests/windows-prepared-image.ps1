[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Image)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\scripts\windows-package-common.ps1')
$Image = [IO.Path]::GetFullPath($Image)
$info = Get-Content -LiteralPath (Join-Path $Image 'support\build-info.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($info.applicationName -ne 'CryptoBench' -or $info.signing -ne 'None') { throw 'This negative test requires an unsigned production-name image.' }
$fixture = Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\tmp'))) ('prepared-image-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$candidate = Join-Path $fixture 'CryptoBench'
Copy-Item -LiteralPath $Image -Destination $candidate -Recurse
$runtimeFile = Join-Path $candidate 'runtime\release'
$originalRuntime = [IO.File]::ReadAllText($runtimeFile)
$incorrectRuntime = [regex]::Replace($originalRuntime, '(?m)^JAVA_VERSION="[^"]+"', 'JAVA_VERSION="21.0.0"')
if ($incorrectRuntime -eq $originalRuntime) { throw 'Runtime fixture version was not changed.' }
[IO.File]::WriteAllText($runtimeFile, $incorrectRuntime)
Write-CryptoBenchPayloadManifest $candidate $info.version
$package = Join-Path $PSScriptRoot '..\scripts\package-windows.ps1'
$arguments = @{ Installer = $true; Release = $true; PreparedAppImage = $candidate; AppVersion = $info.version; OutputDir = (Join-Path $fixture 'output') }
function Assert-RejectedFor([scriptblock]$Action, [string]$Reason) {
    try { & $Action } catch { if ($_.Exception.Message -match $Reason) { return }; throw }
    throw 'Invalid prepared image was accepted.'
}
Assert-RejectedFor { & $package @arguments } 'Prepared image must contain the pinned'
[IO.File]::WriteAllText($runtimeFile, $originalRuntime)
Write-CryptoBenchPayloadManifest $candidate $info.version
Assert-RejectedFor { & $package @arguments -SigningMode Required } 'Prepared image must have a valid timestamped launcher signature'
if (Test-Path -LiteralPath (Join-Path $fixture 'output')) { throw 'Rejected prepared image created output artifacts.' }
Write-Host "PASS prepared release runtime pinning and timestamped-signature requirement. Fixtures: $fixture"
