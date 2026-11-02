# Optional local Windows SMTP settings. The credential file is DPAPI protected for its Windows user.
# Loading this script alone does not read credentials, start the application, or send mail.
function Import-CryptoBenchLocalMail([string]$ProjectRoot) {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { return }
    $qqExplicitMode = [Environment]::GetEnvironmentVariable('CRYPTOBENCH_MAIL_MODE', 'Process')
    if (-not [string]::IsNullOrEmpty($qqExplicitMode)) { return }

    $qqCredentialPath = Join-Path ([IO.Path]::GetFullPath($ProjectRoot)) 'var\secrets\qq-smtp.credential.xml'
    if (-not (Test-Path -LiteralPath $qqCredentialPath)) { return }
    if (-not (Test-Path -LiteralPath $qqCredentialPath -PathType Leaf)) {
        throw 'The local QQ SMTP credential path is not a file. Reconfigure local mail before starting.'
    }

    $qqCredential = $null
    $qqPassword = $null
    $qqPasswordPointer = [IntPtr]::Zero
    try {
        try {
            $qqCredential = Import-Clixml -LiteralPath $qqCredentialPath -ErrorAction Stop
        } catch {
            throw 'Cannot decrypt the local QQ SMTP credential for this Windows user. Run the local mail setup again.'
        }
        if ($qqCredential -isnot [Management.Automation.PSCredential] -or $qqCredential.Password.Length -eq 0) {
            throw 'The local QQ SMTP credential is invalid or empty. Run the local mail setup again.'
        }
        $qqSender = $qqCredential.UserName.Trim().ToLowerInvariant()
        if ($qqSender -notmatch '^[^@\s]+@qq\.com$') {
            throw 'The local SMTP credential must use a QQ mailbox address. Run the local mail setup again.'
        }
        $qqPasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($qqCredential.Password)
        $qqPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($qqPasswordPointer)
        if ([string]::IsNullOrWhiteSpace($qqPassword) -or $qqPassword.Contains("`r") -or $qqPassword.Contains("`n")) {
            throw 'The local QQ SMTP authorization code is invalid. Run the local mail setup again.'
        }
        $qqMailSettings = @{
            'CRYPTOBENCH_MAIL_MODE' = 'smtp'
            'CRYPTOBENCH_MAIL_WORKER_ENABLED' = 'true'
            'CRYPTOBENCH_SMTP_HOST' = 'smtp.qq.com'
            'CRYPTOBENCH_SMTP_PORT' = '465'
            'CRYPTOBENCH_SMTP_USERNAME' = $qqSender
            'CRYPTOBENCH_MAIL_FROM' = $qqSender
            'CRYPTOBENCH_SMTP_SSL' = 'true'
            'CRYPTOBENCH_SMTP_STARTTLS' = 'false'
        }
        foreach ($qqSetting in $qqMailSettings.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($qqSetting.Key, $qqSetting.Value, 'Process')
        }
        [Environment]::SetEnvironmentVariable('CRYPTOBENCH_SMTP_PASSWORD', $qqPassword, 'Process')
    } finally {
        if ($qqPasswordPointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($qqPasswordPointer)
        }
        $qqPassword = $null
        $qqCredential = $null
    }
}
