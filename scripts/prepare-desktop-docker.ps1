[CmdletBinding()]
param(
    [switch]$IncludeGateway,
    [string]$ApplicationDirectory = ''
)
$ErrorActionPreference = 'Stop'
if (-not $ApplicationDirectory) { $ApplicationDirectory = Split-Path -Parent $PSScriptRoot }
$app = [IO.Path]::GetFullPath($ApplicationDirectory)
$support = Join-Path $app 'support'
$info = Get-Content -LiteralPath (Join-Path $support 'build-info.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$version = [string]$info.version
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid packaged application version.' }
if (-not (Get-Command docker.exe -ErrorAction SilentlyContinue)) { throw 'Install Docker Desktop and make docker.exe available on PATH.' }
$engine = & docker.exe info --format '{{.OSType}}'
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop before preparing the sandbox.' }
if (($engine | Out-String).Trim() -ne 'linux') { throw 'Switch Docker Desktop to Linux containers.' }
$builds = @(
    @{ File = 'agent-proxy.Dockerfile'; Image = "cryptobench-agent-proxy:$version" },
    @{ File = 'mock-agent.Dockerfile'; Image = "cryptobench-mock-agent:$version" }
)
if ($IncludeGateway) { $builds += @{ File = 'model-gateway.Dockerfile'; Image = "cryptobench-model-gateway:$version" } }
foreach ($build in $builds) {
    & docker.exe build --file (Join-Path $support ('docker\' + $build.File)) --tag $build.Image (Join-Path $support 'docker')
    if ($LASTEXITCODE -ne 0) { throw "Image preparation failed: $($build.Image). Check network/proxy settings. Desktop configuration was not changed." }
    & docker.exe image inspect $build.Image --format '{{.Id}}'
    if ($LASTEXITCODE -ne 0) { throw 'Built image could not be verified.' }
}
$configDirectory = Join-Path $app 'var\config'
$config = Join-Path $configDirectory 'desktop.properties'
foreach ($path in @($app, (Join-Path $app 'var'), $configDirectory, $config)) {
    if ((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Linked configuration paths are not supported.' }
}
New-Item -ItemType Directory -Path $configDirectory -Force | Out-Null
$settings = [ordered]@{
    'benchmark.docker.enabled' = 'true'
    'benchmark.docker.proxy-image' = "cryptobench-agent-proxy:$version"
    'benchmark.docker.allowed-images[0]' = "cryptobench-mock-agent:$version"
}
if ($IncludeGateway) { $settings['benchmark.gateway.image'] = "cryptobench-model-gateway:$version" }
# Do not enable provider routing without provider configuration, or touch unrelated settings.
$lines = if (Test-Path -LiteralPath $config) { @(Get-Content -LiteralPath $config -Encoding UTF8) } else { @() }
$kept = @($lines | Where-Object {
    $line = $_
    -not @($settings.Keys | Where-Object { $line -match ('^\s*' + [regex]::Escape($_) + '\s*[:=]') }).Count
})
$updated = @($kept) + @($settings.Keys | ForEach-Object { $_ + '=' + $settings[$_] })
$temporary = Join-Path $configDirectory ('.docker-config-' + [Guid]::NewGuid().ToString('N'))
try {
    [IO.File]::WriteAllLines($temporary, [string[]]$updated, (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $config -Force
} finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
Write-Host 'Docker sandbox is prepared. Restart CryptoBench to load the configuration. Gateway provider routing remains opt-in.'
