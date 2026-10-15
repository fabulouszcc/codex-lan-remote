$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
Import-Module Microsoft.PowerShell.Security
$taskStage = 'read-input'
$taskInput = [Console]::In.ReadToEnd() | ConvertFrom-Json
$taskCert = $null
try {
    $taskStage = 'create-user-certificate'
    $taskCert = New-SelfSignedCertificate -Subject 'CN=Codex LAN Remote' -DnsName 'codex-lan.local' -CertStoreLocation 'Cert:\CurrentUser\My' -KeyAlgorithm RSA -KeyLength 3072 -KeyExportPolicy Exportable -NotAfter (Get-Date).AddYears(5)
    $taskPassword = ConvertTo-SecureString -String $taskInput.password -AsPlainText -Force
    $taskStage = 'export-pfx'
    Export-PfxCertificate -Cert $taskCert -FilePath $taskInput.pfx -Password $taskPassword -CryptoAlgorithmOption AES256_SHA256 | Out-Null
    Export-Certificate -Cert $taskCert -FilePath $taskInput.certificate | Out-Null
} catch {
    [Console]::Error.WriteLine(('CERTIFICATE_ERROR:' + $taskStage + ':' + $_.FullyQualifiedErrorId))
    exit 1
} finally {
    if ($taskCert) { Remove-Item -LiteralPath ('Cert:\CurrentUser\My\' + $taskCert.Thumbprint) -Force }
}
