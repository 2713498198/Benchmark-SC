[CmdletBinding()]
param([string]$SenderEmail = '2713498198@qq.com')

$ErrorActionPreference = 'Stop'
$qqSetupRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$qqSetupStatus = Join-Path $qqSetupRoot '.tools\qq-smtp-setup.status.json'
$qqSetupSecret = Join-Path $qqSetupRoot 'var\secrets\qq-smtp.credential.xml'

function Set-QQSetupStatus([string]$State, [string]$Code = '') {
    @{ state = $State; code = $Code; updatedAt = [DateTimeOffset]::UtcNow.ToString('o'); pid = $PID } |
        ConvertTo-Json -Compress | Set-Content -LiteralPath $qqSetupStatus -Encoding UTF8
}
function Read-QQReply($Reader) {
    for ($qqReplyLine = 0; $qqReplyLine -lt 100; $qqReplyLine++) {
        $qqLine = $Reader.ReadLine()
        if ($null -eq $qqLine -or $qqLine -notmatch '^([0-9]{3})([- ])') { throw 'smtp_protocol_failed' }
        if ($Matches[2] -eq ' ') { return [int]$Matches[1] }
    }
    throw 'smtp_protocol_failed'
}
function Test-QQCredential([System.Management.Automation.PSCredential]$Credential) {
    # TLS validates smtp.qq.com using Windows trust; AUTH is tested without sending a message.
    $qqClient = [Net.Sockets.TcpClient]::new()
    $qqTls = $null
    $qqSecretPointer = [IntPtr]::Zero
    try {
        $qqConnect = $qqClient.ConnectAsync('smtp.qq.com', 465)
        if (-not $qqConnect.Wait(10000)) { throw 'smtp_connection_timeout' }
        $qqTls = [Net.Security.SslStream]::new($qqClient.GetStream(), $false)
        $qqTls.ReadTimeout = 10000; $qqTls.WriteTimeout = 10000
        $qqTls.AuthenticateAsClient('smtp.qq.com', $null, [Security.Authentication.SslProtocols]::Tls12, $true)
        $qqReader = [IO.StreamReader]::new($qqTls, [Text.Encoding]::ASCII)
        $qqWriter = [IO.StreamWriter]::new($qqTls, [Text.Encoding]::ASCII)
        $qqWriter.NewLine = "`r`n"; $qqWriter.AutoFlush = $true
        if ((Read-QQReply $qqReader) -ne 220) { throw 'smtp_connection_failed' }
        $qqWriter.WriteLine('EHLO cryptobench.local')
        if ((Read-QQReply $qqReader) -ne 250) { throw 'smtp_protocol_failed' }
        $qqWriter.WriteLine('AUTH LOGIN')
        if ((Read-QQReply $qqReader) -ne 334) { throw 'smtp_authentication_failed' }
        $qqWriter.WriteLine([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Credential.UserName)))
        if ((Read-QQReply $qqReader) -ne 334) { throw 'smtp_authentication_failed' }
        $qqSecretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Credential.Password)
        $qqPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($qqSecretPointer)
        $qqWriter.WriteLine([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($qqPlain)))
        $qqPlain = $null
        if ((Read-QQReply $qqReader) -ne 235) { throw 'smtp_authentication_failed' }
        $qqWriter.WriteLine('QUIT')
    } finally {
        $qqPlain = $null
        if ($qqSecretPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($qqSecretPointer) }
        if ($null -ne $qqTls) { $qqTls.Dispose() }
        $qqClient.Dispose()
    }
}
function Save-QQCredential([System.Management.Automation.PSCredential]$Credential) {
    $qqPrivateDirectory = Split-Path -Parent $qqSetupSecret
    $null = [IO.Directory]::CreateDirectory($qqPrivateDirectory)
    $qqTemporary = Join-Path $qqPrivateDirectory ('qq-smtp-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        # Export-Clixml uses DPAPI for SecureString on Windows, scoped to this Windows user.
        $Credential | Export-Clixml -LiteralPath $qqTemporary -Encoding UTF8
        $qqOwner = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $qqAcl = [Security.AccessControl.FileSecurity]::new()
        $qqAcl.SetOwner($qqOwner)
        $qqAcl.SetAccessRuleProtection($true, $false)
        $qqAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($qqOwner, 'FullControl', 'Allow'))
        Set-Acl -LiteralPath $qqTemporary -AclObject $qqAcl
        if (Test-Path -LiteralPath $qqSetupSecret) {
            [IO.File]::Replace($qqTemporary, $qqSetupSecret, $null)
            Set-Acl -LiteralPath $qqSetupSecret -AclObject $qqAcl
        } else { [IO.File]::Move($qqTemporary, $qqSetupSecret) }
    } finally {
        if (Test-Path -LiteralPath $qqTemporary) { Remove-Item -LiteralPath $qqTemporary -Force }
    }
}

$null = [IO.Directory]::CreateDirectory((Split-Path -Parent $qqSetupStatus))
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$qqForm = [Windows.Forms.Form]::new()
$qqForm.Text = 'CryptoBench - QQ SMTP 设置'
$qqForm.ClientSize = [Drawing.Size]::new(530, 340)
$qqForm.StartPosition = 'CenterScreen'; $qqForm.FormBorderStyle = 'FixedDialog'
$qqForm.MaximizeBox = $false; $qqForm.MinimizeBox = $false
$qqForm.Font = [Drawing.Font]::new('Microsoft YaHei UI', 10)
$qqIntro = [Windows.Forms.Label]::new()
$qqIntro.Text = "请先在 QQ 邮箱设置中启用 SMTP，并获取邮箱授权码。`r`n授权码只保存在本机，不会发送到聊天或写入项目代码。"
$qqIntro.SetBounds(20, 18, 490, 52); $qqForm.Controls.Add($qqIntro)
$qqEmailLabel = [Windows.Forms.Label]::new(); $qqEmailLabel.Text = '发件邮箱'
$qqEmailLabel.SetBounds(20, 85, 100, 28); $qqForm.Controls.Add($qqEmailLabel)
$qqEmailBox = [Windows.Forms.TextBox]::new(); $qqEmailBox.Text = $SenderEmail
$qqEmailBox.SetBounds(130, 82, 370, 28); $qqForm.Controls.Add($qqEmailBox)
$qqCodeLabel = [Windows.Forms.Label]::new(); $qqCodeLabel.Text = 'SMTP 授权码'
$qqCodeLabel.SetBounds(20, 130, 110, 28); $qqForm.Controls.Add($qqCodeLabel)
$qqCodeBox = [Windows.Forms.TextBox]::new(); $qqCodeBox.UseSystemPasswordChar = $true
$qqCodeBox.MaxLength = 128; $qqCodeBox.SetBounds(130, 127, 370, 28); $qqForm.Controls.Add($qqCodeBox)
$qqNote = [Windows.Forms.Label]::new()
$qqNote.Text = "请输入邮箱授权码，不是 QQ 登录密码。`r`n保存前只验证 SMTP 登录，不会发送邮件。`r`n保存后重启网站生效；以后 start.cmd 会自动加载。"
$qqNote.SetBounds(20, 173, 490, 75); $qqForm.Controls.Add($qqNote)
$qqResult = [Windows.Forms.Label]::new(); $qqResult.SetBounds(20, 250, 490, 35)
$qqForm.Controls.Add($qqResult)
$qqSave = [Windows.Forms.Button]::new(); $qqSave.Text = '验证并保存'
$qqSave.SetBounds(250, 293, 120, 32); $qqForm.Controls.Add($qqSave)
$qqCancel = [Windows.Forms.Button]::new(); $qqCancel.Text = '取消'
$qqCancel.SetBounds(385, 293, 115, 32); $qqForm.Controls.Add($qqCancel)
$qqForm.AcceptButton = $qqSave; $qqForm.CancelButton = $qqCancel
$script:qqSaved = $false
$qqCancel.Add_Click({ $qqForm.Close() })
$qqSave.Add_Click({
    $qqCredential = $null
    try {
        $qqAddress = $qqEmailBox.Text.Trim()
        if ($qqAddress -notmatch '^[A-Za-z0-9._+-]+@qq\.com$' -or [string]::IsNullOrWhiteSpace($qqCodeBox.Text)) {
            $qqResult.Text = '请填写 QQ 发件邮箱及 SMTP 授权码。'; return
        }
        $qqSecure = ConvertTo-SecureString -String $qqCodeBox.Text.Trim() -AsPlainText -Force
        $qqCredential = [Management.Automation.PSCredential]::new($qqAddress, $qqSecure)
        $qqCodeBox.Clear(); $qqSave.Enabled = $false; $qqCancel.Enabled = $false
        $qqResult.Text = '正在通过加密连接验证 SMTP 登录…'
        Set-QQSetupStatus 'validating'; [Windows.Forms.Application]::DoEvents()
        Test-QQCredential $qqCredential
        Save-QQCredential $qqCredential
        $script:qqSaved = $true; Set-QQSetupStatus 'configured'
        [Windows.Forms.MessageBox]::Show('配置已加密保存。请重启网站以启用真实邮件发送，以后通过 start.cmd 启动即可自动加载。', 'CryptoBench', 'OK', 'Information') | Out-Null
        $qqForm.Close()
    } catch {
        $qqSafeCode = if ($_.Exception.Message -match '^smtp_[a-z_]+$') { $_.Exception.Message } else { 'smtp_setup_failed' }
        Set-QQSetupStatus 'failed' $qqSafeCode
        $qqResult.Text = if ($qqSafeCode -eq 'smtp_authentication_failed') { 'SMTP 登录失败，请检查是否启用 SMTP 及授权码。' } else { '连接或保存失败，请检查网络后重试。' }
    } finally {
        if ($null -ne $qqCredential) { $qqCredential.Password.Dispose() }
        $qqSave.Enabled = $true; $qqCancel.Enabled = $true
    }
})
$qqForm.Add_FormClosed({ if (-not $script:qqSaved) { Set-QQSetupStatus 'cancelled' } })
Set-QQSetupStatus 'awaiting_input'
try { $null = $qqForm.ShowDialog() } finally { $qqCodeBox.Clear(); $qqForm.Dispose() }
