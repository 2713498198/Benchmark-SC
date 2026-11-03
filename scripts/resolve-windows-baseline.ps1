[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Repository,
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [Parameter(Mandatory = $true)][string]$OutputDir
)
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid GitHub repository.' }
$pages = & gh api --paginate --slurp "repos/$Repository/releases"
if ($LASTEXITCODE -ne 0) { throw 'Cannot query prior releases; upgrade verification cannot be skipped.' }
$releases = @($pages | ConvertFrom-Json | ForEach-Object { $_ } | ForEach-Object { $_ })
$stable = @($releases | Where-Object { -not $_.draft -and -not $_.prerelease -and $_.tag_name -match '^v\d+\.\d+\.\d+$' -and [version]$_.tag_name.Substring(1) -lt [version]$Version } |
    Sort-Object { [version]$_.tag_name.Substring(1) } -Descending)
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
if (-not $stable.Count) {
    [ordered]@{ tag = 'first-release'; installer = $null; version = $null } | ConvertTo-Json | Set-Content (Join-Path $OutputDir 'baseline.json') -Encoding UTF8
    return
}
$previous = $stable[0]
$previousVersion = $previous.tag_name.Substring(1)
$installerName = "CryptoBench-$previousVersion.exe"
if (-not ($previous.assets | Where-Object name -eq $installerName) -or -not ($previous.assets | Where-Object name -eq 'SHA256SUMS.csv')) { throw 'Previous stable release is missing its installer or checksums.' }
& gh release download $previous.tag_name --repo $Repository --pattern $installerName --pattern 'SHA256SUMS.csv' --dir $OutputDir
if ($LASTEXITCODE -ne 0) { throw 'Could not download the previous release.' }
$expected = Import-Csv (Join-Path $OutputDir 'SHA256SUMS.csv') | Where-Object Path -eq $installerName
$installer = Join-Path ([IO.Path]::GetFullPath($OutputDir)) $installerName
if (-not $expected -or $expected.Algorithm -ne 'SHA256' -or (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash -ne $expected.Hash) { throw 'Previous installer checksum mismatch.' }
[ordered]@{ tag = $previous.tag_name; installer = $installer; version = $previousVersion } | ConvertTo-Json | Set-Content (Join-Path $OutputDir 'baseline.json') -Encoding UTF8
