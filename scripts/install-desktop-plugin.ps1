param([string]$CodexPath, [string]$LegacyRoot, [string]$DataDirectory)
$ErrorActionPreference = 'Stop'
$taskBundle = $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $taskBundle '.agents\plugins\marketplace.json'))) { throw '请从完整桌面插件发布包中运行此安装脚本' }
if (-not $CodexPath) {
    $taskCodexFiles = @(Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA 'OpenAI\Codex\bin') -Directory -ErrorAction SilentlyContinue | ForEach-Object { Get-Item -LiteralPath (Join-Path $_.FullName 'codex.exe') -ErrorAction SilentlyContinue } | Sort-Object LastWriteTime -Descending)
    if (-not $taskCodexFiles.Count) { throw '未检测到桌面 Codex，请用 -CodexPath 指定桌面内置 codex.exe' }
    $CodexPath = $taskCodexFiles[0].FullName
}
if (-not $DataDirectory) { $DataDirectory = Join-Path $env:LOCALAPPDATA 'CodexLAN' }
New-Item -ItemType Directory -Force -Path $DataDirectory | Out-Null
$taskSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $DataDirectory /inheritance:r /grant:r ('*' + $taskSid + ':(OI)(CI)F') '*S-1-5-18:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw '无法保护插件数据目录' }
$taskConfiguration = Join-Path $DataDirectory 'config.local.json'
if (-not (Test-Path -LiteralPath $taskConfiguration)) {
    if ($LegacyRoot) {
        $taskOldConfig = Get-Content -LiteralPath (Join-Path $LegacyRoot '.local\gateway.local.json') -Raw | ConvertFrom-Json
        $taskConfig = [ordered]@{ mode='existing'; host=$taskOldConfig.host; port=$taskOldConfig.port; name=$taskOldConfig.name; codexBinary=$CodexPath; threads=$taskOldConfig.threads; allowSelectedChats=$taskOldConfig.allowSelectedChats; autoStart=$true; easytier=@{name='';cidr='';peers=@();listenPort=11011;rpcPort=15899;devName='codex-lan-et'} }
        $taskSecrets = Join-Path $DataDirectory 'secrets'; New-Item -ItemType Directory -Force -Path $taskSecrets | Out-Null
        foreach ($taskName in @('gateway-identity.local.json','gateway.p12','gateway.crt','devices.local.json')) {
            $taskTarget = Join-Path $taskSecrets $taskName
            if (Test-Path -LiteralPath $taskTarget) { throw '新数据目录已有证书或授权，请先核对，安装器不会覆盖' }
            Copy-Item -LiteralPath (Join-Path $LegacyRoot ('.local\secrets\' + $taskName)) -Destination $taskTarget
        }
    } else {
        $taskConfig = [ordered]@{ mode='existing'; host=''; port=9443; name='Codex 电脑'; codexBinary=$CodexPath; threads=@(); allowSelectedChats=$false; autoStart=$false; easytier=@{name='';cidr='';peers=@();listenPort=11011;rpcPort=15899;devName='codex-lan-et'} }
    }
    $taskConfig | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $taskConfiguration -Encoding utf8
}
$taskCurrentConfig = Get-Content -LiteralPath $taskConfiguration -Raw | ConvertFrom-Json
if (-not $taskCurrentConfig.codexBinary -or -not (Test-Path -LiteralPath $taskCurrentConfig.codexBinary)) {
    $taskCurrentConfig.codexBinary = $CodexPath
    $taskCurrentConfig | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $taskConfiguration -Encoding utf8
}
$taskSourcesJson = & $CodexPath plugin marketplace list --json
if ($LASTEXITCODE -ne 0) { throw '无法读取已有插件目录' }
$taskPriorSource = ($taskSourcesJson | ConvertFrom-Json).marketplaces | Where-Object { $_.name -eq 'codex-lan-local' } | Select-Object -First 1
$taskSourceChanged = $taskPriorSource -and ([IO.Path]::GetFullPath($taskPriorSource.root).TrimEnd('\') -ne [IO.Path]::GetFullPath($taskBundle).TrimEnd('\'))
if ($taskSourceChanged) {
    & $CodexPath plugin marketplace remove codex-lan-local --json | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '无法更新手机连接插件目录来源' }
}
if (-not $taskPriorSource -or $taskSourceChanged) {
    & $CodexPath plugin marketplace add $taskBundle --json
    if ($LASTEXITCODE -ne 0) {
        if ($taskSourceChanged) { & $CodexPath plugin marketplace add $taskPriorSource.root --json | Out-Null }
        throw '注册本地插件目录失败，已尝试恢复旧来源'
    }
}
$taskInstalledJson = & $CodexPath plugin add codex-lan-desktop@codex-lan-local --json
if ($LASTEXITCODE -ne 0) { throw '安装插件失败' }
$taskInstalledJson | Write-Output
$taskInstalled = $taskInstalledJson | ConvertFrom-Json
& (Join-Path $taskInstalled.installedPath 'scripts\prepare-desktop-native.ps1') -PluginDirectory $taskInstalled.installedPath
& (Join-Path $taskInstalled.installedPath 'scripts\install-settings-shortcuts.ps1') -PluginDirectory $taskInstalled.installedPath -DataDirectory $DataDirectory
& (Join-Path $taskInstalled.installedPath 'scripts\configure-desktop-autostart.ps1') -DataDirectory $DataDirectory -Start
Write-Output '手机连接插件已安装。当前聊天可能需要刷新插件或在新的聊天中加载工具；无需重装 Android App。'
Write-Output ('插件数据目录：' + $DataDirectory)
