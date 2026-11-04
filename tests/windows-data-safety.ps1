$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\scripts\windows-package-common.ps1')
$testRoot = Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\tmp'))) ('desktop-safety-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
function Assert-Rejected([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw $Message }
}
$image = Join-Path $testRoot 'CryptoBench'
New-Item -ItemType Directory -Path $image | Out-Null
Set-Content -LiteralPath (Join-Path $image 'program.txt') -Value 'fixture'
Write-CryptoBenchPayloadManifest $image '1.0.0'
Assert-CryptoBenchReplaceableImage $image
Set-Content -LiteralPath (Join-Path $image 'user.txt') -Value 'must remain'
Assert-Rejected { Assert-CryptoBenchReplaceableImage $image } 'Unknown files were accepted for replacement'
Remove-Item -LiteralPath (Join-Path $image 'user.txt')
New-Item -ItemType Directory -Path (Join-Path $image 'var\data'),(Join-Path $image 'var\secrets'),(Join-Path $image 'var\config\empty'),(Join-Path $image 'support') -Force | Out-Null
Set-Content -LiteralPath (Join-Path $image 'var\data\cryptobench.mv.db') -Value 'fixture database'
Set-Content -LiteralPath (Join-Path $image 'var\secrets\mail.key') -Value 'fixture key'
Set-Content -LiteralPath (Join-Path $image 'support\build-info.json') -Value '{"version":"1.0.0"}'
Assert-Rejected { Assert-CryptoBenchReplaceableImage $image } 'User data was accepted for replacement'
$archive = Join-Path $testRoot 'backup.zip'
$tool = Join-Path $PSScriptRoot '..\scripts\desktop-data.ps1'
& $tool -Action Backup -Archive $archive -ApplicationDirectory $image
$databaseHash = (Get-FileHash -LiteralPath (Join-Path $image 'var\data\cryptobench.mv.db')).Hash
$keyHash = (Get-FileHash -LiteralPath (Join-Path $image 'var\secrets\mail.key')).Hash
Remove-Item -LiteralPath (Join-Path $image 'var\config\empty')
Set-Content -LiteralPath (Join-Path $image 'var\data\cryptobench.mv.db') -Value 'changed'
Set-Content -LiteralPath (Join-Path $image 'var\secrets\mail.key') -Value 'changed'
& $tool -Action Restore -Archive $archive -ApplicationDirectory $image
if ((Get-FileHash -LiteralPath (Join-Path $image 'var\data\cryptobench.mv.db')).Hash -ne $databaseHash) { throw 'Database restore failed' }
if ((Get-FileHash -LiteralPath (Join-Path $image 'var\secrets\mail.key')).Hash -ne $keyHash) { throw 'Secret key restore failed' }
foreach ($privatePath in @($archive, (Join-Path $image 'var\secrets\mail.key'))) {
    $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $rules = @((Get-Acl -LiteralPath $privatePath).GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if (-not $rules.Count -or @($rules | Where-Object { $_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Value -ne $currentSid }).Count) { throw "Backup or restored key is accessible to other identities: $privatePath" }
}
if (-not (Test-Path -LiteralPath (Join-Path $image 'var\config\empty') -PathType Container)) { throw 'Empty persistent directory was not restored' }
if (-not (Get-ChildItem -LiteralPath $testRoot -Filter 'cryptobench-before-restore-*.zip')) { throw 'Restore did not first back up existing data' }
$lock = [IO.File]::Open((Join-Path $image 'var\run\instance.lock'), 'Open', 'ReadWrite', 'None')
try { Assert-Rejected { & $tool -Action Backup -Archive (Join-Path $testRoot 'active.zip') -ApplicationDirectory $image } 'Backup accepted an active instance' }
finally { $lock.Dispose() }
Assert-Rejected { & $tool -Action Backup -Archive (Join-Path $image 'unsafe.zip') -ApplicationDirectory $image } 'Backup inside application was accepted'
$fresh = Join-Path $testRoot 'fresh'
New-Item -ItemType Directory -Path $fresh | Out-Null
Set-Content -LiteralPath (Join-Path $fresh 'program.txt') -Value 'new'
Write-CryptoBenchPayloadManifest $fresh '1.0.1'
Assert-Rejected { Publish-CryptoBenchArtifact $fresh $image -Image } 'Publish overwrote user data'
if ((Get-FileHash -LiteralPath (Join-Path $image 'var\data\cryptobench.mv.db')).Hash -ne $databaseHash) { throw 'Rejected replacement modified data' }
# A rejected restore must leave the live database and key unchanged. Test the
# actual ZIP reader, including Windows aliases that can escape a lexical check.
function Write-ArchiveFixture([string]$Path, [string]$EntryName, [string]$Hash) {
    $zip = [IO.Compression.ZipFile]::Open($Path, 'Create')
    try {
        $manifestEntry = $zip.CreateEntry('backup-manifest.json')
        $writer = New-Object IO.StreamWriter($manifestEntry.Open())
        try { $writer.Write((@{ schema = 'cryptobench-backup-v1'; files = @(@{ path = $EntryName; sha256 = $Hash }) } | ConvertTo-Json -Depth 5)) } finally { $writer.Dispose() }
        $entry = $zip.CreateEntry($EntryName)
        $writer = New-Object IO.StreamWriter($entry.Open())
        try { $writer.Write('corrupt fixture') } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
}
$invalidEntries = @('var/data/cryptobench.mv.db','var/../escaped.txt','var/RUN/instance.lock','var/run./instance.lock','var/data/CON.txt')
for ($index = 0; $index -lt $invalidEntries.Count; $index++) {
    $invalidArchive = Join-Path $testRoot ("invalid-$index.zip")
    Write-ArchiveFixture $invalidArchive $invalidEntries[$index] ('0' * 64)
    Assert-Rejected { & $tool -Action Restore -Archive $invalidArchive -ApplicationDirectory $image } ('Unsafe or corrupt archive accepted: ' + $invalidEntries[$index])
    if ((Get-FileHash -LiteralPath (Join-Path $image 'var\data\cryptobench.mv.db')).Hash -ne $databaseHash -or (Get-FileHash -LiteralPath (Join-Path $image 'var\secrets\mail.key')).Hash -ne $keyHash) { throw 'Rejected restore changed live data' }
}
$brokenArchive = Join-Path $testRoot 'truncated.zip'
[IO.File]::WriteAllText($brokenArchive, 'not a complete ZIP')
Assert-Rejected { & $tool -Action Restore -Archive $brokenArchive -ApplicationDirectory $image } 'Unreadable ZIP was accepted'
if (Get-ChildItem -LiteralPath $image -Directory -Filter '.restore-*') { throw 'Rejected archive left extraction staging behind' }
Write-Host "PASS desktop data safety, full backup/restore, key preservation and maintenance lock. Fixtures: $testRoot"
