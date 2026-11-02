# Shared by the source launcher and Windows packaging. Process-local settings only.
function Get-CryptoBenchJdkVersion([string]$Executable) {
    # Windows PowerShell treats redirected native stderr as an error record.
    # Java can write harmless launcher notices there and still exit successfully.
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $process.StartInfo.FileName = $Executable
    $process.StartInfo.Arguments = '--version'
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        $process.Start() | Out-Null
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) {
            $process.Kill()
            throw 'JDK version check timed out.'
        }
        return [PSCustomObject]@{
            ExitCode = $process.ExitCode
            Output = $stdout.GetAwaiter().GetResult() + "`n" + $stderr.GetAwaiter().GetResult()
        }
    } finally { $process.Dispose() }
}

function Test-CryptoBenchJdk([string]$Candidate, [switch]$ExplainFailure) {
    if (-not $Candidate) { return $false }
    $java = Join-Path $Candidate 'bin\java.exe'
    $javac = Join-Path $Candidate 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $javac)) {
        if ($ExplainFailure) { Write-Warning "JDK executables are missing under: $Candidate" }
        return $false
    }
    try {
        foreach ($name in @('java', 'javac')) {
            $version = Get-CryptoBenchJdkVersion (Join-Path $Candidate "bin\$name.exe")
            $pattern = if ($name -eq 'java') { '(?m)^(openjdk|java) 21[.\s]' } else { '(?m)^javac 21[.\s]' }
            if ($version.ExitCode -ne 0 -or $version.Output -notmatch $pattern) {
                if ($ExplainFailure) {
                    Write-Warning "$name --version failed in $Candidate (exit $($version.ExitCode)): $($version.Output.Trim())"
                }
                return $false
            }
        }
        return $true
    } catch {
        if ($ExplainFailure) { Write-Warning "JDK probe failed in ${Candidate}: $($_.Exception.Message)" }
        return $false
    }
}

function Initialize-CryptoBenchJava([string]$ProjectRoot) {
    # Use the modules shipped with this PowerShell host. CI can launch Windows
    # PowerShell with a PowerShell 7 module path, breaking command auto-loading.
    Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility') -ErrorAction Stop
    Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Archive') -ErrorAction Stop
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
            if (-not $jdk -or -not (Test-CryptoBenchJdk $jdk.FullName -ExplainFailure)) { throw 'Downloaded archive does not contain a working JDK 21.' }
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
