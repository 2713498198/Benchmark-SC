param(
    [switch]$PrepareOnly,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArguments
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Import-CryptoBenchEnvironment([string]$ProjectRoot) {
    $localEnvPath = Join-Path $ProjectRoot '.vscode/local.env'
    if (-not (Test-Path -LiteralPath $localEnvPath -PathType Leaf)) { return }

    foreach ($line in Get-Content -LiteralPath $localEnvPath -Encoding UTF8) {
        $entry = $line.Trim().TrimStart([char]0xFEFF)
        if (-not $entry -or $entry.StartsWith('#') -or -not $entry.Contains('=')) { continue }

        $name, $value = $entry.Split('=', 2)
        $name = $name.Trim()
        $value = $value.Trim()
        if (-not $name) { continue }
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        # Explicit process variables take precedence over the local development file.
        if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

Import-CryptoBenchEnvironment $root
. (Join-Path $PSScriptRoot 'java-env.ps1')
. (Join-Path $PSScriptRoot 'local-mail.ps1')
try {
    if (-not $PrepareOnly -and -not $MavenArguments) { Import-CryptoBenchLocalMail $root }
    Initialize-CryptoBenchJava $root
    Push-Location $root
    try {
        if ($PrepareOnly) { $MavenArguments = @('--version') }
        elseif (-not $MavenArguments) {
            $port = if ($env:PORT) { $env:PORT } else { '4173' }
            Write-Host "Starting CryptoBench at http://127.0.0.1:$port"
            $MavenArguments = @('spring-boot:run')
        }
        & (Join-Path $root 'mvnw.cmd') @MavenArguments
        $result = $LASTEXITCODE
        if ($result -ne 0) { Write-Host 'Startup/build failed. See the error above and docs/SETUP.md.' }
        exit $result
    } finally { Pop-Location }
} catch {
    Write-Host "ERROR: $($_.Exception.Message)"
    exit 1
}
