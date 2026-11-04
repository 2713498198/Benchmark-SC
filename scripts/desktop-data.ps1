[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Backup', 'Restore')][string]$Action,
    [Parameter(Mandatory = $true)][string]$Archive,
    [string]$ApplicationDirectory = ''
)
$ErrorActionPreference = 'Stop'
if (-not $ApplicationDirectory) { $ApplicationDirectory = Split-Path -Parent $PSScriptRoot }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$app = [IO.Path]::GetFullPath($ApplicationDirectory).TrimEnd('\')
$archivePath = [IO.Path]::GetFullPath($Archive)
if ($archivePath.StartsWith($app + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Store backups outside the application directory.' }
$data = Join-Path $app 'var'

function Assert-Plain([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Linked data path is not supported: $cursor" }
        $parent = Split-Path $cursor -Parent
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
}
function Get-DataEntries([string]$Directory) {
    $pending = New-Object 'System.Collections.Generic.Queue[string]'
    $pending.Enqueue($Directory)
    while ($pending.Count) {
        foreach ($item in (Get-ChildItem -LiteralPath $pending.Dequeue() -Force)) {
            Assert-Plain $item.FullName
            if ($item.FullName -eq (Join-Path $Directory 'run')) { continue }
            $item
            if ($item.PSIsContainer) { $pending.Enqueue($item.FullName) }
        }
    }
}
function Protect-PrivatePath([string]$Path, [switch]$Directory) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $security = if ($Directory) { New-Object Security.AccessControl.DirectorySecurity } else { New-Object Security.AccessControl.FileSecurity }
    $security.SetOwner($identity)
    $security.SetAccessRuleProtection($true, $false)
    $inheritance = if ($Directory) { [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit' } else { [Security.AccessControl.InheritanceFlags]::None }
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', $inheritance, 'None', 'Allow')
    $security.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $security
}
function Write-Backup([string]$Destination) {
    Assert-Plain $Destination
    if (Test-Path -LiteralPath $Destination) { throw "Backup already exists; choose a new filename: $Destination" }
    $temporaryArchive = $Destination + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    $zip = [IO.Compression.ZipFile]::Open($temporaryArchive, 'Create')
    try {
        $rows = @()
        $directories = @()
        foreach ($file in @(Get-DataEntries $data)) {
            $relative = $file.FullName.Substring($data.Length + 1).Replace('\', '/')
            if ($file.PSIsContainer) { $directories += 'var/' + $relative; continue }
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, 'var/' + $relative, 'Optimal') | Out-Null
            $rows += [ordered]@{ path = 'var/' + $relative; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
        }
        $version = 'unknown'
        $info = Join-Path $app 'support\build-info.json'
        if (Test-Path -LiteralPath $info) { $version = (Get-Content -LiteralPath $info -Raw -Encoding UTF8 | ConvertFrom-Json).version }
        $entry = $zip.CreateEntry('backup-manifest.json')
        $writer = New-Object IO.StreamWriter($entry.Open(), (New-Object Text.UTF8Encoding($false)))
        try { $writer.Write(([ordered]@{ schema = 'cryptobench-backup-v1'; version = $version; createdAt = [DateTime]::UtcNow.ToString('o'); directories = $directories; files = $rows } | ConvertTo-Json -Depth 5)) }
        finally { $writer.Dispose() }
    } catch {
        $zip.Dispose()
        if (Test-Path -LiteralPath $temporaryArchive) { Remove-Item -LiteralPath $temporaryArchive -Force }
        throw
    } finally { $zip.Dispose() }
    try {
        Protect-PrivatePath $temporaryArchive
        Move-Item -LiteralPath $temporaryArchive -Destination $Destination
    } catch {
        if (Test-Path -LiteralPath $temporaryArchive) { Remove-Item -LiteralPath $temporaryArchive -Force }
        throw
    }
}

function Resolve-BackupEntry([string]$Directory, [string]$Name) {
    if (-not $Name.StartsWith('var/', [StringComparison]::Ordinal) -or $Name -match '\\|:|(^|/)\.\.?(/|$)') { throw 'Unsafe backup entry.' }
    $parts = $Name.Split('/')
    if ($parts.Length -lt 2 -or $parts[1] -ieq 'run') { throw 'Backup must not contain transient instance control files.' }
    foreach ($part in $parts) {
        if (-not $part -or $part -match '[. ]$|[<>"|?*\x00-\x1f]' -or $part -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)') {
            throw 'Backup entry contains an ambiguous or reserved Windows filename.'
        }
    }
    $target = [IO.Path]::GetFullPath((Join-Path $Directory $Name.Replace('/', '\')))
    if (-not $target.StartsWith($Directory + '\var\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Backup path escaped staging.' }
    return $target
}

Assert-Plain $app
Assert-Plain $archivePath
Assert-Plain $data
if (-not (Test-Path -LiteralPath $data -PathType Container)) {
    if ($Action -eq 'Restore') { New-Item -ItemType Directory -Path $data | Out-Null }
    else { throw "No data directory exists: $data" }
}
# The lock is held throughout backup/restore, so a new launcher cannot start mid-copy.
$run = Join-Path $data 'run'
Assert-Plain $run
New-Item -ItemType Directory -Path $run -Force | Out-Null
$lockPath = Join-Path $run 'instance.lock'
Assert-Plain $lockPath
$lock = $null
$databaseProbe = $null
try {
    try { $lock = [IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'CryptoBench is running or another maintenance operation is active. Exit it before backup/restore.' }
    # Old launchers have no instance.lock. Probe their H2 file too, then release the
    # read handle before copying; the native executable check protects old versions.
    $executable = Join-Path $app 'CryptoBench.exe'
    if (Test-Path -LiteralPath $executable) {
        if (Get-Process -Name CryptoBench -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $executable }) { throw 'Exit CryptoBench before backup/restore.' }
        try { $probe = [IO.File]::Open($executable, 'Open', 'Read', 'None'); $probe.Dispose() }
        catch { throw 'The application executable is in use. Exit CryptoBench first.' }
    }
    $database = Join-Path $data 'data\cryptobench.mv.db'
    if (Test-Path -LiteralPath $database) {
        try { $databaseProbe = [IO.File]::Open($database, 'Open', 'Read', 'Read') }
        catch { throw 'The H2 database is in use. Stop all application processes before backup/restore.' }
    }
    New-Item -ItemType Directory -Path (Split-Path $archivePath) -Force | Out-Null
    if ($Action -eq 'Backup') {
        Write-Backup $archivePath
        Write-Host "Backup created: $archivePath"
    } else {
        if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) { throw 'Backup archive does not exist.' }
        $staging = Join-Path $app ('.restore-' + [Guid]::NewGuid().ToString('N'))
        $retainStaging = $false
        New-Item -ItemType Directory -Path $staging | Out-Null
        $zip = $null
        try {
            Protect-PrivatePath $staging -Directory
            $zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
            $manifestEntry = $zip.GetEntry('backup-manifest.json')
            if (-not $manifestEntry) { throw 'Backup manifest is missing.' }
            $reader = New-Object IO.StreamReader($manifestEntry.Open())
            try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
            if ($manifest.schema -ne 'cryptobench-backup-v1') { throw 'Unsupported backup format.' }
            $expected = @{}
            foreach ($row in @($manifest.files)) {
                Resolve-BackupEntry $staging $row.path | Out-Null
                if ($expected.ContainsKey($row.path)) { throw 'Duplicate backup path.' }
                $expected[$row.path] = $row.sha256
            }
            # Optional for compatibility with archives written before empty folders
            # were recorded. Validate all directory names before changing live data.
            $folders = @{}
            foreach ($name in @($manifest.directories)) {
                if ($null -eq $name) { continue }
                $folder = Resolve-BackupEntry $staging $name
                if ($folders.ContainsKey($name) -or $expected.ContainsKey($name)) { throw 'Duplicate backup directory or conflicting file path.' }
                $folders[$name] = $true
                New-Item -ItemType Directory -Path $folder -Force | Out-Null
            }
            $seen = @{}
            foreach ($entry in $zip.Entries) {
                if ($entry.FullName -eq 'backup-manifest.json') { continue }
                $target = Resolve-BackupEntry $staging $entry.FullName
                if ($seen.ContainsKey($entry.FullName) -or -not $expected.ContainsKey($entry.FullName)) { throw 'Unexpected backup entry.' }
                New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $false)
                if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $expected[$entry.FullName]) { throw 'Backup checksum mismatch.' }
                $seen[$entry.FullName] = $true
            }
            if ($seen.Count -ne $expected.Count) { throw 'Backup files are missing.' }
            $secrets = Join-Path $staging 'var\secrets'
            if (Test-Path -LiteralPath $secrets -PathType Container) { Protect-PrivatePath $secrets -Directory }
            $before = Join-Path (Split-Path $archivePath) ('cryptobench-before-restore-' + [Guid]::NewGuid().ToString('N') + '.zip')
            Write-Backup $before
            if ($databaseProbe) { $databaseProbe.Dispose(); $databaseProbe = $null }
            $previous = Join-Path $staging 'previous'
            New-Item -ItemType Directory -Path $previous | Out-Null
            $moved = @()
            $installed = @()
            $retainStaging = $true
            try {
                foreach ($child in (Get-ChildItem -LiteralPath $data -Force | Where-Object Name -ne 'run')) {
                    Assert-Plain $child.FullName
                    Move-Item -LiteralPath $child.FullName -Destination $previous
                    $moved += $child.Name
                }
                if (Test-Path -LiteralPath (Join-Path $staging 'var')) {
                    foreach ($child in (Get-ChildItem -LiteralPath (Join-Path $staging 'var') -Force)) {
                        Move-Item -LiteralPath $child.FullName -Destination $data
                        $installed += $child.Name
                    }
                }
            } catch {
                foreach ($name in $installed) { Move-Item -LiteralPath (Join-Path $data $name) -Destination (Join-Path $staging 'var') }
                foreach ($name in $moved) { Move-Item -LiteralPath (Join-Path $previous $name) -Destination $data }
                $retainStaging = $false
                throw
            }
            $retainStaging = $false
            Write-Host "Data restored. Previous data backup: $before"
        } finally {
            if ($zip) { $zip.Dispose() }
            $resolved = [IO.Path]::GetFullPath($staging)
            if (-not $resolved.StartsWith($app + '\.restore-', [StringComparison]::OrdinalIgnoreCase)) { throw 'Restore path escaped application.' }
            if ($retainStaging) { Write-Warning "Restore recovery files were retained: $resolved" }
            else { Remove-Item -LiteralPath $resolved -Recurse -Force }
        }
    }
} finally {
    if ($databaseProbe) { $databaseProbe.Dispose() }
    if ($lock) { $lock.Dispose() }
}
