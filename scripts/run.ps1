param(
    [switch]$PrepareOnly,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArguments
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'java-env.ps1')
try {
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
