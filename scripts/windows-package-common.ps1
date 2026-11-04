$ErrorActionPreference = 'Stop'

function Assert-CryptoBenchPlainPath([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked packaging paths are not supported: $cursor" }
        }
        $parent = Split-Path -Parent $cursor
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
}

function Get-CryptoBenchPayloadRows([string]$Directory) {
    $prefix = [IO.Path]::GetFullPath($Directory).TrimEnd('\') + '\'
    Assert-CryptoBenchPlainPath $Directory
    # Enumerate one level at a time so traversal never enters junctions.
    $pending = New-Object 'System.Collections.Generic.Queue[string]'
    $pending.Enqueue($Directory)
    $rows = @()
    while ($pending.Count) {
        foreach ($item in (Get-ChildItem -LiteralPath $pending.Dequeue() -Force)) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked payload entry: $($item.FullName)" }
            if ($item.PSIsContainer) { $pending.Enqueue($item.FullName); continue }
            $relative = $item.FullName.Substring($prefix.Length).Replace('\', '/')
            if ($relative -eq '.cryptobench-payload.json') { continue }
            $rows += [pscustomobject]@{ path = $relative; sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash; size = $item.Length }
        }
    }
    return @($rows | Sort-Object path)
}

function Write-CryptoBenchPayloadManifest([string]$Directory, [string]$Version) {
    [ordered]@{ schema = 'cryptobench-payload-v1'; version = $Version; files = @(Get-CryptoBenchPayloadRows $Directory) } |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $Directory '.cryptobench-payload.json') -Encoding UTF8
}

function Assert-CryptoBenchReplaceableImage([string]$Directory) {
    Assert-CryptoBenchPlainPath $Directory
    if (-not (Test-Path -LiteralPath $Directory)) { return }
    if (Test-Path -LiteralPath (Join-Path $Directory 'var')) {
        throw "Existing app image contains user data (var). Keep it and choose a new OutputDir: $Directory"
    }
    $manifestPath = Join-Path $Directory '.cryptobench-payload.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Existing output is not a verified build artifact. Choose a new OutputDir: $Directory"
    }
    Assert-CryptoBenchPlainPath $manifestPath
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.schema -ne 'cryptobench-payload-v1') { throw 'Unknown payload manifest' }
    $actual = @(Get-CryptoBenchPayloadRows $Directory)
    $expected = @($manifest.files | Sort-Object path)
    if ($actual.Count -ne $expected.Count) { throw "Existing output contains unknown or missing files: $Directory" }
    for ($index = 0; $index -lt $actual.Count; $index++) {
        if ($actual[$index].path -cne $expected[$index].path -or $actual[$index].sha256 -ne $expected[$index].sha256) {
            throw "Existing output was modified; refusing replacement: $($actual[$index].path)"
        }
    }
    # Empty unknown directories are data too; only paths represented in the manifest are allowed.
    $known = @{}
    foreach ($row in $expected) {
        $parent = Split-Path ($row.path.Replace('/', '\')) -Parent
        while ($parent) { $known[$parent] = $true; $parent = Split-Path $parent -Parent }
    }
    $prefix = [IO.Path]::GetFullPath($Directory).TrimEnd('\') + '\'
    foreach ($child in (Get-ChildItem -LiteralPath $Directory -Directory -Recurse -Force)) {
        if (-not $known.ContainsKey($child.FullName.Substring($prefix.Length))) { throw "Unknown directory in existing output: $($child.FullName)" }
    }
    $exe = Join-Path $Directory 'CryptoBench.exe'
    if (Test-Path -LiteralPath $exe) {
        if (Get-Process -Name CryptoBench -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe }) { throw "Existing app image is running: $Directory" }
        try { $probe = [IO.File]::Open($exe, 'Open', 'Read', 'None'); $probe.Dispose() }
        catch { throw "Existing app image is running or in use: $Directory" }
    }
}

function Publish-CryptoBenchArtifact([string]$Source, [string]$Destination, [switch]$Image) {
    Assert-CryptoBenchPlainPath $Destination
    if ($Image) { Assert-CryptoBenchReplaceableImage $Destination }
    $parent = [IO.Path]::GetFullPath((Split-Path -Parent $Destination)).TrimEnd('\') + '\'
    $previous = $Destination + '.previous-' + [Guid]::NewGuid().ToString('N')
    if (-not ([IO.Path]::GetFullPath($previous)).StartsWith($parent, [StringComparison]::OrdinalIgnoreCase)) { throw 'Backup path escaped output' }
    $hadPrevious = Test-Path -LiteralPath $Destination
    if ($hadPrevious) { Move-Item -LiteralPath $Destination -Destination $previous }
    try { Move-Item -LiteralPath $Source -Destination $Destination }
    catch {
        if ($hadPrevious -and -not (Test-Path -LiteralPath $Destination)) { Move-Item -LiteralPath $previous -Destination $Destination }
        throw
    }
    if ($hadPrevious) {
        # Recheck after rename; if someone started the old image in the meantime, retain it.
        try {
            if ($Image) { Assert-CryptoBenchReplaceableImage $previous }
            Remove-Item -LiteralPath $previous -Recurse -Force
        } catch { Write-Warning "New artifact is ready; retained previous output for safety: $previous" }
    }
}
