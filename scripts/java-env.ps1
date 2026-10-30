# Shared by the source launcher and Windows packaging. Process-local settings only.
function Test-CryptoBenchJdk([string]$Candidate) {
    if (-not $Candidate) { return $false }
    $java = Join-Path $Candidate 'bin\java.exe'
    $javac = Join-Path $Candidate 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $javac)) { return $false }
    try {
        $runtimeVersion = & $java --version 2>$null
        if ($LASTEXITCODE -ne 0 -or ($runtimeVersion -join "`n") -notmatch '(?m)^(openjdk|java) 21[.\s]') { return $false }
        $compilerVersion = & $javac --version 2>$null
        return ($LASTEXITCODE -eq 0 -and ($compilerVersion -join "`n") -match '^javac 21[.\s]')
    } catch { return $false }
}

function Initialize-CryptoBenchJava([string]$ProjectRoot) {
    $architecture = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
    $arch = switch ($architecture) { 'AMD64' { 'x64' } 'ARM64' { 'aarch64' } default { $architecture } }
    $entry = Import-Csv -LiteralPath (Join-Path $ProjectRoot 'scripts\jdk-distributions.tsv') -Delimiter "`t" |
        Where-Object { $_.os -eq 'windows' -and $_.arch -eq $arch } | Select-Object -First 1
    $toolsDir = Join-Path $ProjectRoot '.tools'
    $cached = if ($entry) { Join-Path $toolsDir "temurin-$($entry.version)-windows-$arch" } else { $null }
    $systemJava = Get-Command java.exe -ErrorAction SilentlyContinue
    $systemHome = if ($systemJava) { Split-Path (Split-Path $systemJava.Source) } else { $null }
    $selected = $null
    foreach ($candidate in @((Join-Path $toolsDir 'jdk-21'), $cached, $env:JAVA_HOME, $systemHome)) {
        if (Test-CryptoBenchJdk $candidate) { $selected = $candidate; break }
    }
    if (-not $selected) {
        if ($env:CRYPTOBENCH_NO_JDK_DOWNLOAD -eq '1') { throw 'JDK 21 was not found. Set JAVA_HOME to a full JDK 21, or put it in .tools\jdk-21. Automatic download is disabled.' }
        if (-not $entry) { throw "No bundled JDK download for Windows $arch. Install JDK 21 and set JAVA_HOME." }
        if (Test-Path -LiteralPath $cached) { throw "Cached JDK is invalid: $cached. Move it aside and retry, or set JAVA_HOME to JDK 21." }
        New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
        $staging = Join-Path $toolsDir ('.jdk-download-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $staging | Out-Null
        try {
            $archive = Join-Path $staging 'jdk.zip'
            Write-Host "Downloading Temurin $($entry.version) ($arch) into .tools..."
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            $downloadParameters = @{ Uri = $entry.url; OutFile = $archive; UseBasicParsing = $true }
            if ($env:HTTPS_PROXY) { $downloadParameters.Proxy = $env:HTTPS_PROXY }
            $oldProgress = $ProgressPreference
            try { $ProgressPreference = 'SilentlyContinue'; Invoke-WebRequest @downloadParameters } finally { $ProgressPreference = $oldProgress }
            if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw 'JDK SHA-256 verification failed; the archive will not be used.' }
            Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $staging 'expanded')
            $jdk = Get-ChildItem -LiteralPath (Join-Path $staging 'expanded') -Directory | Select-Object -First 1
            if (-not $jdk -or -not (Test-CryptoBenchJdk $jdk.FullName)) { throw 'Downloaded archive does not contain a working JDK 21.' }
            Move-Item -LiteralPath $jdk.FullName -Destination $cached
            $selected = $cached
        } catch {
            throw "JDK setup failed: $($_.Exception.Message) See docs/SETUP.md for proxy and manual installation instructions."
        } finally {
            $resolvedStaging = [IO.Path]::GetFullPath($staging)
            $allowedPrefix = [IO.Path]::GetFullPath($toolsDir).TrimEnd('\') + '\.jdk-download-'
            if ($resolvedStaging.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedStaging)) {
                Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
            }
        }
    }
    $env:JAVA_HOME = [IO.Path]::GetFullPath($selected)
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
    $env:MAVEN_USER_HOME = Join-Path $ProjectRoot '.tools\maven-user-home'
    Write-Host "JAVA_HOME: $env:JAVA_HOME"
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') --version | Out-Host
}
