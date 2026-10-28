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
    [string]$Commit = ''
)

$ErrorActionPreference = 'Stop'
$ReleaseDir = [IO.Path]::GetFullPath($ReleaseDir)
if (-not (Test-Path -LiteralPath $ReleaseDir -PathType Container)) {
    throw "Release directory does not exist: $ReleaseDir"
}

$artifacts = @(Get-ChildItem -LiteralPath $ReleaseDir -File |
    Where-Object { $_.Extension -in @('.exe', '.zip') } |
    Sort-Object Name)
if (-not $artifacts) { throw "No Windows release artifacts found in $ReleaseDir" }

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
        database = 'var/data/cryptobench.mv.db relative to the application working directory'
        upgrade = 'Install upgrades into the existing directory to retain local data'
        uninstall = 'User data is not part of the installed application payload'
    }
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ReleaseDir 'release-manifest.json') -Encoding UTF8
Write-Host "Created release manifest and SHA-256 list in $ReleaseDir"
