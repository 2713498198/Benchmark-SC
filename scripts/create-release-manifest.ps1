[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseDir,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v\d+\.\d+\.\d+$')]
    [string]$Tag,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-fA-F0-9]{40}$')][string]$Commit,
    [Parameter(Mandatory = $true)][string]$BuildInfo,
    [ValidateSet('None', 'Required')][string]$SigningMode = 'None',
    [Parameter(Mandatory = $true)][string]$UpgradeBaseline,
    [switch]$UpgradeVerified
)

$ErrorActionPreference = 'Stop'
$ReleaseDir = [IO.Path]::GetFullPath($ReleaseDir)
if ($Tag -ne "v$Version") { throw 'Tag and Version must match.' }
if ($UpgradeBaseline -ne 'first-release' -and -not $UpgradeVerified) { throw 'A previous release requires successful upgrade verification.' }
$build = Get-Content -LiteralPath $BuildInfo -Raw -Encoding UTF8 | ConvertFrom-Json
if ($build.version -ne $Version) { throw 'Runtime metadata version does not match the release.' }
if ($build.java -isnot [string]) { throw 'Runtime metadata must be plain release text without PowerShell filesystem properties.' }
if (-not (Test-Path -LiteralPath $ReleaseDir -PathType Container)) {
    throw "Release directory does not exist: $ReleaseDir"
}

$artifacts = @(Get-ChildItem -LiteralPath $ReleaseDir -File |
    Where-Object { $_.Extension -in @('.exe', '.zip') } |
    Sort-Object Name)
if (-not $artifacts) { throw "No Windows release artifacts found in $ReleaseDir" }
$expected = @("CryptoBench-$Version.exe", "CryptoBench-$Tag-portable.zip")
if (@(Compare-Object ($artifacts.Name | Sort-Object) ($expected | Sort-Object)).Count) { throw 'Release must contain exactly the expected installer and portable ZIP.' }
if ($SigningMode -eq 'Required') {
    $signature = Get-AuthenticodeSignature -LiteralPath (Join-Path $ReleaseDir "CryptoBench-$Version.exe")
    if ($signature.Status -ne 'Valid' -or -not $signature.TimeStamperCertificate) { throw 'Signed releases require a valid timestamped installer.' }
    if ($build.signing -ne 'Required') { throw 'Portable launcher must also be signed.' }
}

$rows = @($artifacts | ForEach-Object {
    $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
    [pscustomobject]@{
        Algorithm = $hash.Algorithm
        Hash = $hash.Hash
        Path = $_.Name
        SizeBytes = $_.Length
    }
})
$rows | Select-Object Algorithm, Hash, Path | ConvertTo-Csv -NoTypeInformation |
    Set-Content -LiteralPath (Join-Path $ReleaseDir 'SHA256SUMS.csv') -Encoding UTF8

$manifest = [ordered]@{
    schema = 'cryptobench-windows-release-v1'
    version = $Version
    tag = $Tag
    commit = $Commit
    generatedAt = [DateTime]::UtcNow.ToString('o')
    channel = if ($SigningMode -eq 'Required') { 'signed' } else { 'unsigned-test' }
    signing = [ordered]@{ mode = $SigningMode; timestampRequired = ($SigningMode -eq 'Required') }
    runtime = [ordered]@{ architecture = 'x64'; release = $build.java; jdkBytes = $build.jdkBytes; runtimeBytes = $build.runtimeBytes }
    verification = [ordered]@{ upgradeBaseline = $UpgradeBaseline; upgradeVerified = [bool]$UpgradeVerified }
    artifacts = @($rows | ForEach-Object {
        [ordered]@{
            name = $_.Path
            sha256 = $_.Hash
            sizeBytes = $_.SizeBytes
            kind = if ($_.Path.EndsWith('.exe')) { 'installer' } else { 'portable' }
        }
    })
    requirements = [ordered]@{
        operatingSystem = 'Windows 10/11 x64'
        runtime = 'Bundled Java 21 runtime'
        administratorRights = $false
        defaultPort = 4173
        docker = 'Optional; required only for Docker Agent sandbox execution'
    }
    data = [ordered]@{
        database = 'var/data/cryptobench.mv.db relative to the application installation directory'
        backup = 'Stop CryptoBench and back up all persistent var contents, including var/secrets/mail.key; var/run is transient'
        upgrade = 'Install upgrades into the existing directory to retain local data'
        uninstall = 'User data is not part of the installed application payload'
    }
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ReleaseDir 'release-manifest.json') -Encoding UTF8
Write-Host "Created release manifest and SHA-256 list in $ReleaseDir"
