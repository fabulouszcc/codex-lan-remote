param([Parameter(Mandatory=$true)][string]$PluginDirectory)
$ErrorActionPreference = 'Stop'
$taskDestination = Join-Path $PluginDirectory 'vendor\easytier\Packet.dll'
$taskExpected = 'C7C03A87EAC7243CCBE331554624B18803010B740E311FC8CFDDB573096EACAC'
if ((Test-Path -LiteralPath $taskDestination) -and (Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash -eq $taskExpected) { return }
# Obtain the required Packet.dll directly from the official EasyTier archive
# for this user's installation; do not re-distribute it in our plugin ZIP.
$taskCache = Join-Path $env:LOCALAPPDATA 'CodexLAN\downloads\easytier-2.6.4'
New-Item -ItemType Directory -Force -Path $taskCache | Out-Null
$taskArchive = Join-Path $taskCache 'easytier-windows-x86_64-v2.6.4.zip'
if (-not (Test-Path -LiteralPath $taskArchive)) {
    Invoke-WebRequest 'https://github.com/EasyTier/EasyTier/releases/download/v2.6.4/easytier-windows-x86_64-v2.6.4.zip' -OutFile ($taskArchive + '.part')
    Move-Item -LiteralPath ($taskArchive + '.part') -Destination $taskArchive
}
if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash -ne '27AF91E270E554709B048BD32327FEFD2DFCE5062AE1E8701AF7550C6F525F84') { throw '官方 EasyTier 安装依赖摘要不匹配' }
Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskCache -Force
$taskPacket = Join-Path $taskCache 'easytier-windows-x86_64\Packet.dll'
if ((Get-FileHash -LiteralPath $taskPacket -Algorithm SHA256).Hash -ne $taskExpected) { throw 'Packet.dll 摘要不匹配' }
Copy-Item -LiteralPath $taskPacket -Destination $taskDestination -Force
