param([string]$DataDirectory, [switch]$Start, [switch]$StartOnly, [switch]$Highest)
$ErrorActionPreference = 'Stop'
if (-not $DataDirectory) { $DataDirectory = Join-Path $env:LOCALAPPDATA 'CodexLAN' }
$taskPlugin = Split-Path -Parent $PSScriptRoot
if (-not ('CodexLANPhysicalPath' -as [type])) {
    Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class CodexLANPhysicalPath {
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern SafeFileHandle CreateFile(string name,uint access,uint share,IntPtr security,uint creation,uint flags,IntPtr template);
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern uint GetFinalPathNameByHandle(SafeFileHandle handle,StringBuilder path,uint size,uint flags);
  public static string Resolve(string path) {
    using(var handle=CreateFile(path,0,7,IntPtr.Zero,3,0x02000000,IntPtr.Zero)) {
      if(handle.IsInvalid) throw new System.IO.IOException("Cannot resolve background directory");
      var buffer=new StringBuilder(32768);
      uint count=GetFinalPathNameByHandle(handle,buffer,(uint)buffer.Capacity,0);
      if(count==0||count>=buffer.Capacity) throw new System.IO.IOException("Cannot resolve background directory");
      string result=buffer.ToString();
      if(result.StartsWith(@"\\?\UNC\")) return @"\\"+result.Substring(8);
      return result.StartsWith(@"\\?\")?result.Substring(4):result;
    }
  }
}
'@
}
$taskVirtualRoot = [IO.Path]::GetFullPath($DataDirectory).TrimEnd('\')
$DataDirectory = [CodexLANPhysicalPath]::Resolve($DataDirectory)
$taskPlugin = [CodexLANPhysicalPath]::Resolve($taskPlugin)
$taskIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$taskSid = $taskIdentity.User.Value
$taskUser = $taskIdentity.Name
$taskFullRoot = [IO.Path]::GetFullPath($DataDirectory).TrimEnd('\')
$taskHash = [Security.Cryptography.SHA256]::Create()
try { $taskSuffix = ([BitConverter]::ToString($taskHash.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskFullRoot.ToLowerInvariant())))).Replace('-','').Substring(0,12) } finally { $taskHash.Dispose() }
$taskName = 'Codex LAN Remote-' + $taskSid + '-' + $taskSuffix
$taskMarker = 'Codex LAN Remote managed background'
$taskOldHash = [Security.Cryptography.SHA256]::Create()
try { $taskOldSuffix = ([BitConverter]::ToString($taskOldHash.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskVirtualRoot.ToLowerInvariant())))).Replace('-','').Substring(0,12) } finally { $taskOldHash.Dispose() }
$taskOldName = 'Codex LAN Remote-' + $taskSid + '-' + $taskOldSuffix
if ($taskOldName -ne $taskName) {
    $taskOld = Get-ScheduledTask -TaskName $taskOldName -ErrorAction SilentlyContinue
    if ($taskOld -and $taskOld.Description -eq $taskMarker) { Unregister-ScheduledTask -TaskName $taskOldName -Confirm:$false -ErrorAction Stop }
}
$taskPrior = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($taskPrior -and $taskPrior.Description -ne $taskMarker) { throw '同名后台任务不属于本插件，未修改' }
if ($StartOnly) {
    if (-not $taskPrior) { throw '手机连接自动恢复任务尚未注册' }
    Start-ScheduledTask -TaskName $taskName -ErrorAction Stop
    return
}
$taskConfig = Get-Content -LiteralPath (Join-Path $taskFullRoot 'config.local.json') -Raw | ConvertFrom-Json
$taskMetadata = Join-Path $taskFullRoot 'autostart.local.json'
if ($taskConfig.autoStart -cne $true) {
    # Unregistering prevents future launches; do not terminate a current connection.
    if ($taskPrior) { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false }
    [IO.File]::WriteAllText($taskMetadata, '{"kind":"windows-task","configured":false,"elevated":false}', [Text.UTF8Encoding]::new($false))
    return
}
$taskRunner = Join-Path $taskPlugin 'scripts\run-desktop-background.ps1'
if (-not (Test-Path -LiteralPath $taskRunner)) { throw '后台启动脚本缺失，请重新安装完整插件包' }
$taskElevated = $Highest -or ($taskPrior -and [string]$taskPrior.Principal.RunLevel -eq 'Highest')
$taskLevel = if ($taskElevated) { 'Highest' } else { 'Limited' }
if ($Highest -and -not ([Security.Principal.WindowsPrincipal]::new($taskIdentity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw '配置管理员后台需先允许 Windows 管理员授权' }
$taskPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskLaunch = @'
$ErrorActionPreference='Stop'
try {
    [IO.File]::WriteAllText('__STATUS__','{"phase":"task-entry"}')
    $env:PSModulePath=[IO.Path]::Combine($env:SystemRoot,'System32\WindowsPowerShell\v1.0\Modules')
    & '__RUNNER__' -DataDirectory '__ROOT__'
    exit $LASTEXITCODE
} catch {
    [IO.File]::WriteAllText('__FALLBACK__','{"phase":"launcher-failed","code":'+$_.Exception.HResult+'}')
    exit 1
}
'@
$taskLaunch = $taskLaunch.Replace('__STATUS__', (Join-Path $taskFullRoot 'background-status.local.json').Replace("'","''")).Replace('__RUNNER__', $taskRunner.Replace("'","''")).Replace('__ROOT__', $taskFullRoot.Replace("'","''")).Replace('__FALLBACK__', (Join-Path ([IO.Path]::GetTempPath()) 'codex-lan-startup-status.local.json').Replace("'","''"))
$taskArgs = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($taskLaunch))
$taskAction = New-ScheduledTaskAction -Execute $taskPowerShell -Argument $taskArgs
$taskPrincipal = New-ScheduledTaskPrincipal -UserId $taskUser -LogonType Interactive -RunLevel $taskLevel
$taskTrigger = New-ScheduledTaskTrigger -AtLogOn -User $taskUser
$taskSettings = New-ScheduledTaskSettingsSet -Hidden -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $taskName -Action $taskAction -Principal $taskPrincipal -Trigger $taskTrigger -Settings $taskSettings -Description $taskMarker -Force -ErrorAction Stop | Out-Null
$taskValue = @{kind='windows-task'; configured=$true; elevated=[bool]$taskElevated}
[IO.File]::WriteAllText($taskMetadata, ($taskValue | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
if ($Start) { Start-ScheduledTask -TaskName $taskName -ErrorAction Stop }
