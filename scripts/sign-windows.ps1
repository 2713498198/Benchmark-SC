[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$File,
    [ValidateSet('None', 'Required')][string]$Mode = 'None',
    [string]$CertificateThumbprint = $env:CRYPTOBENCH_SIGNING_THUMBPRINT,
    [string]$TimestampUrl = 'http://timestamp.digicert.com'
)
$ErrorActionPreference = 'Stop'
if ($Mode -eq 'None') { return }
if ($CertificateThumbprint -notmatch '^[A-Fa-f0-9]{40}$') { throw 'Required signing needs CRYPTOBENCH_SIGNING_THUMBPRINT (CurrentUser certificate store).' }
$signtool = Get-Command signtool.exe -ErrorAction SilentlyContinue
if (-not $signtool) { throw 'Install Windows SDK signtool.exe and add it to PATH before signed packaging.' }
(Get-Item -LiteralPath $File).IsReadOnly = $false
& $signtool.Source sign /sha1 $CertificateThumbprint /fd SHA256 /tr $TimestampUrl /td SHA256 $File
if ($LASTEXITCODE -ne 0) { throw "Authenticode signing failed: $File" }
& $signtool.Source verify /pa /all /tw $File
if ($LASTEXITCODE -ne 0) { throw "Authenticode verification failed: $File" }
$signature = Get-AuthenticodeSignature -LiteralPath $File
if ($signature.Status -ne 'Valid' -or -not $signature.TimeStamperCertificate) { throw 'A valid, timestamped signature is required.' }
