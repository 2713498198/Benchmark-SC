$ErrorActionPreference = 'Stop'
$mailTestProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $mailTestProject 'scripts\local-mail.ps1')
$mailTestRoot = Join-Path $mailTestProject ('target\local-mail-fixture-' + [Guid]::NewGuid().ToString('N'))
$mailTestDirectory = Join-Path $mailTestRoot 'var\secrets'
$mailTestPath = Join-Path $mailTestDirectory 'qq-smtp.credential.xml'
$mailTestKeys = @('CRYPTOBENCH_MAIL_MODE','CRYPTOBENCH_MAIL_WORKER_ENABLED','CRYPTOBENCH_SMTP_HOST','CRYPTOBENCH_SMTP_PORT','CRYPTOBENCH_SMTP_USERNAME','CRYPTOBENCH_MAIL_FROM','CRYPTOBENCH_SMTP_SSL','CRYPTOBENCH_SMTP_STARTTLS','CRYPTOBENCH_SMTP_PASSWORD')
# Run this fixture in its own PowerShell process; inherited values are cleared without reading them.
foreach ($mailTestKey in $mailTestKeys) {
    [Environment]::SetEnvironmentVariable($mailTestKey, $null, 'Process')
}
function Assert-MailFixture([bool]$Condition, [string]$Label) {
    if (-not $Condition) { throw ('Local mail fixture assertion failed: ' + $Label) }
}
try {
    Import-CryptoBenchLocalMail $mailTestRoot
    Assert-MailFixture (-not $env:CRYPTOBENCH_MAIL_MODE) 'missing configuration leaves default mode'
    New-Item -ItemType Directory -Path $mailTestDirectory -Force | Out-Null
    $mailTestSecure = ConvertTo-SecureString 'fixture-only-authorization-code' -AsPlainText -Force
    $mailTestCredential = New-Object Management.Automation.PSCredential('123456789@qq.com', $mailTestSecure)
    $mailTestCredential | Export-Clixml -LiteralPath $mailTestPath
    Assert-MailFixture (-not ([IO.File]::ReadAllText($mailTestPath).Contains('fixture-only-authorization-code'))) 'DPAPI file does not store plaintext'
    Import-CryptoBenchLocalMail $mailTestRoot
    Assert-MailFixture ($env:CRYPTOBENCH_MAIL_MODE -eq 'smtp') 'SMTP enabled'
    Assert-MailFixture ($env:CRYPTOBENCH_MAIL_WORKER_ENABLED -eq 'true') 'worker enabled'
    Assert-MailFixture ($env:CRYPTOBENCH_SMTP_HOST -eq 'smtp.qq.com' -and $env:CRYPTOBENCH_SMTP_PORT -eq '465') 'QQ SMTP endpoint'
    Assert-MailFixture ($env:CRYPTOBENCH_SMTP_SSL -eq 'true' -and $env:CRYPTOBENCH_SMTP_STARTTLS -eq 'false') 'implicit TLS'
    Assert-MailFixture ($env:CRYPTOBENCH_SMTP_USERNAME -eq '123456789@qq.com' -and $env:CRYPTOBENCH_MAIL_FROM -eq '123456789@qq.com') 'sender bound to credential'
    Assert-MailFixture ($env:CRYPTOBENCH_SMTP_PASSWORD -eq 'fixture-only-authorization-code') 'credential decrypted in process environment'

    [IO.File]::WriteAllText($mailTestPath, 'invalid credential fixture')
    $env:CRYPTOBENCH_MAIL_MODE = 'outbox'
    Import-CryptoBenchLocalMail $mailTestRoot
    Assert-MailFixture ($env:CRYPTOBENCH_MAIL_MODE -eq 'outbox') 'explicit mode takes precedence even over unreadable local file'
    [Environment]::SetEnvironmentVariable('CRYPTOBENCH_MAIL_MODE', $null, 'Process')
    $mailTestFailure = $false
    try { Import-CryptoBenchLocalMail $mailTestRoot } catch {
        $mailTestFailure = $true
        Assert-MailFixture (-not $_.Exception.Message.Contains('fixture-only-authorization-code')) 'failure hides credential'
    }
    Assert-MailFixture $mailTestFailure 'invalid credential prevents fallback'
    Assert-MailFixture (-not $env:CRYPTOBENCH_MAIL_MODE) 'invalid credential does not partially enable SMTP'
    Write-Host 'PASS local DPAPI SMTP configuration: loading, TLS settings, explicit override, missing and invalid credentials. No app started or mail sent.'
} finally {
    foreach ($mailTestKey in $mailTestKeys) {
        [Environment]::SetEnvironmentVariable($mailTestKey, $null, 'Process')
    }
    $mailTestAllowed = [IO.Path]::GetFullPath((Join-Path $mailTestProject 'target')).TrimEnd('\') + '\local-mail-fixture-'
    $mailTestResolved = [IO.Path]::GetFullPath($mailTestRoot)
    if ($mailTestResolved.StartsWith($mailTestAllowed, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $mailTestResolved)) {
        Remove-Item -LiteralPath $mailTestResolved -Recurse -Force
    }
}
