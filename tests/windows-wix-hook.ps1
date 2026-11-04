$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\tmp'))
$fixture = Join-Path $root ('wix-hook-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\desktop\windows\CryptoBench-post-image.wsf') -Destination (Join-Path $fixture 'CryptoBenchQA-post-image.wsf')
$bundle = '<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi" xmlns:util="http://schemas.microsoft.com/wix/UtilExtension"><Fragment><Component Id="Data"><util:RemoveFolderEx Property="PERSISTEDDIR" On="uninstall" /></Component></Fragment></Wix>'
$main = '<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi"><Product><InstallExecuteSequence><RemoveExistingProducts Before="CostInitialize" /></InstallExecuteSequence><InstallUISequence /></Product></Wix>'
[IO.File]::WriteAllText((Join-Path $fixture 'bundle.wxf'), $bundle)
[IO.File]::WriteAllText((Join-Path $fixture 'main.wxs'), $main)
& cscript.exe //nologo (Join-Path $fixture 'CryptoBenchQA-post-image.wsf')
if ($LASTEXITCODE -ne 0) { throw 'WiX hook failed on the supported structure.' }
$generated = [xml](Get-Content -LiteralPath (Join-Path $fixture 'main.wxs') -Raw)
$actions = @($generated.Wix.Product.CustomAction)
$check = $actions | Where-Object Id -eq 'CryptoBenchCheckRunning'
if ($check.BinaryKey -ne 'CryptoBenchRunningScript' -or $check.JScriptCall -ne 'CryptoBenchCheckRunning') { throw 'Running check is not an embedded script action.' }
if ($generated.Wix.Product.InstallExecuteSequence.RemoveExistingProducts.After -ne 'InstallInitialize') { throw 'Old version removal is not transactional and after the running check.' }
if ((Get-Content -LiteralPath (Join-Path $fixture 'bundle.wxf') -Raw) -match 'RemoveFolderEx') { throw 'Recursive data cleanup remains.' }
# Microsoft JScript parses the actual embedded script; no MSI session is invoked.
& cscript.exe //nologo (Join-Path $fixture 'cryptobench-running.js')
if ($LASTEXITCODE -ne 0) { throw 'Embedded maintenance script is not valid Microsoft JScript.' }
if (-not ((Get-Content -LiteralPath (Join-Path $fixture 'cryptobench-running.js') -Raw).Contains('CryptoBenchQA.exe'))) { throw 'Isolated application identity was not preserved.' }
# Applying to an unknown/already-patched layout must refuse to produce a package.
$rejected = Start-Process -FilePath cscript.exe -ArgumentList @('//nologo', ('"' + (Join-Path $fixture 'CryptoBenchQA-post-image.wsf') + '"')) -Wait -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $fixture 'expected-failure.log')
if ($rejected.ExitCode -eq 0) { throw 'Hook silently accepted an unsupported cleanup layout.' }
Write-Host "PASS WiX structure guard, user-data preservation and maintenance script parsing. Fixture: $fixture"
