[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Shortcut,
    [Parameter(Mandatory = $true)][string]$Executable
)
$ErrorActionPreference = 'Stop'
$shell = New-Object -ComObject WScript.Shell
if (-not (Test-Path -LiteralPath $Shortcut)) { throw "Missing installed shortcut: $Shortcut" }
$target = $shell.CreateShortcut($Shortcut).TargetPath
if ([IO.Path]::GetFullPath($target) -ne [IO.Path]::GetFullPath($Executable)) { throw 'Unexpected shortcut target.' }
$process = Start-Process -FilePath $Shortcut -PassThru -WindowStyle Hidden
if (-not $process) { throw 'Shortcut did not start a process.' }
$process.WaitForExit()
exit $process.ExitCode
