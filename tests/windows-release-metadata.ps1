$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\tmp'))
$fixture = Join-Path $root ('release-metadata-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
$release = Join-Path $fixture 'release'
New-Item -ItemType Directory -Path $release | Out-Null
Set-Content -LiteralPath (Join-Path $release 'CryptoBench-1.2.3.exe') -Value 'unsigned test artifact'
Set-Content -LiteralPath (Join-Path $release 'CryptoBench-v1.2.3-portable.zip') -Value 'portable test artifact'
$build = Join-Path $fixture 'build-info.json'
Set-Content -LiteralPath $build -Value '{"version":"1.2.3","java":"Temurin fixture","jdkBytes":200,"runtimeBytes":100,"signing":"None"}'
$script = Join-Path $PSScriptRoot '..\scripts\create-release-manifest.ps1'
$arguments = @{ ReleaseDir = $release; Version = '1.2.3'; Tag = 'v1.2.3'; Commit = ('a' * 40); BuildInfo = $build; UpgradeBaseline = 'first-release' }
& $script @arguments
$manifest = Get-Content -LiteralPath (Join-Path $release 'release-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.commit -ne ('a' * 40) -or $manifest.channel -ne 'unsigned-test' -or $manifest.verification.upgradeBaseline -ne 'first-release') { throw 'Release provenance or unsigned channel is incorrect.' }
foreach ($artifact in $manifest.artifacts) {
    if ($artifact.sha256 -ne (Get-FileHash -LiteralPath (Join-Path $release $artifact.name)).Hash) { throw 'Manifest hash mismatch.' }
}
function Assert-Rejected([scriptblock]$Action) {
    $failed = $false
    try { & $Action } catch { $failed = $true }
    if (-not $failed) { throw 'Invalid release metadata was accepted.' }
}
$arguments.Tag = 'v1.2.4'
Assert-Rejected { & $script @arguments }
$arguments.Tag = 'v1.2.3'
$arguments.UpgradeBaseline = 'v1.2.2'
Assert-Rejected { & $script @arguments }
$arguments.UpgradeVerified = $true
& $script @arguments
$arguments.SigningMode = 'Required'
Assert-Rejected { & $script @arguments }
$arguments.SigningMode = 'None'
Assert-Rejected { & (Join-Path $PSScriptRoot '..\scripts\sign-windows.ps1') -File (Join-Path $release 'CryptoBench-1.2.3.exe') -Mode Required -CertificateThumbprint '' }
Set-Content -LiteralPath (Join-Path $release 'unexpected.exe') -Value 'must not publish'
Assert-Rejected { & $script @arguments }
Write-Host "PASS release provenance, exact asset set, hashes, upgrade gate and unsigned-release rejection. Fixtures: $fixture"
