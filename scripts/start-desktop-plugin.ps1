param([switch]$Administrator, [string]$DataDirectory)
$ErrorActionPreference = 'Stop'
$taskPlugin = Split-Path -Parent $PSScriptRoot
if (-not $DataDirectory) { $DataDirectory = Join-Path $env:LOCALAPPDATA 'CodexLAN' }
if ($Administrator) {
    $taskArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'), '-DataDirectory', ('"' + $DataDirectory + '"'))
    $taskIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $taskPrincipal = [Security.Principal.WindowsPrincipal]::new($taskIdentity)
    if (-not $taskPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        # UAC is shown by Windows; no automatic elevation or credential bypass.
        Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList ($taskArguments + '-Administrator')
        return
    }
    & (Join-Path $PSScriptRoot 'configure-desktop-autostart.ps1') -DataDirectory $DataDirectory -Highest
    $taskEndpointPath = Join-Path $DataDirectory 'manager.local.json'
    if (Test-Path -LiteralPath $taskEndpointPath) {
        $taskEndpoint = Get-Content -LiteralPath $taskEndpointPath -Raw | ConvertFrom-Json
        if ($taskEndpoint.origin -notmatch '^http://127\.0\.0\.1:\d+$') { throw '本机管理地址无效' }
        try { Invoke-RestMethod ($taskEndpoint.origin + '/api/shutdown') -Method Post -Headers @{ Authorization=('Bearer ' + $taskEndpoint.token) } -ContentType 'application/json' -Body '{}' | Out-Null } catch { }
        for ($taskAttempt = 0; $taskAttempt -lt 40 -and (Test-Path -LiteralPath $taskEndpointPath); $taskAttempt++) { Start-Sleep -Milliseconds 250 }
        if (Test-Path -LiteralPath $taskEndpointPath) { throw '旧后台仍在退出，请稍后重新打开设置' }
    }
}
$env:CODEX_LAN_DATA_DIR = $DataDirectory
if ($Administrator) {
    $taskSavedConfig = Get-Content -LiteralPath (Join-Path $DataDirectory 'config.local.json') -Raw | ConvertFrom-Json
    if ($taskSavedConfig.autoStart -eq $true) {
        # Scheduler owns the elevated daemon, rather than a Codex child process.
        & (Join-Path $PSScriptRoot 'configure-desktop-autostart.ps1') -DataDirectory $DataDirectory -StartOnly
        $taskReady = $false
        for ($taskAttempt = 0; $taskAttempt -lt 80; $taskAttempt++) {
            if (Test-Path -LiteralPath $taskEndpointPath) {
                try {
                    $taskEndpoint = Get-Content -LiteralPath $taskEndpointPath -Raw | ConvertFrom-Json
                    $taskStatus = Invoke-RestMethod ($taskEndpoint.origin + '/api/status') -Headers @{Authorization=('Bearer ' + $taskEndpoint.token)} -TimeoutSec 2
                    $taskReady = $taskStatus.kind -eq 'codex-lan-manager'
                } catch { }
            }
            if ($taskReady) { break }
            Start-Sleep -Milliseconds 250
        }
        if (-not $taskReady) { throw 'Windows 后台任务未就绪，请重新打开设置检查启动状态' }
        & (Join-Path $taskPlugin 'runtime\node.exe') (Join-Path $taskPlugin 'desktop\open.mjs')
        if ($LASTEXITCODE -ne 0) { throw '后台已启动，但无法打开设置页面' }
        return
    }
    # Keep the elevated host alive for the lifetime of its daemon. Do not rely on
    # a short-lived URL opener to own a privileged background process on Windows.
    $taskDaemon = Start-Process -FilePath (Join-Path $taskPlugin 'runtime\node.exe') -ArgumentList @(('"' + (Join-Path $taskPlugin 'desktop\daemon.mjs') + '"'), '--data', ('"' + $DataDirectory + '"')) -WindowStyle Hidden -PassThru
    $taskReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 80; $taskAttempt++) {
        $taskDaemon.Refresh()
        if ($taskDaemon.HasExited) { throw '管理员后台已退出，请重新打开设置检查权限或端口' }
        if (Test-Path -LiteralPath $taskEndpointPath) {
            try { $taskNewEndpoint = Get-Content -LiteralPath $taskEndpointPath -Raw | ConvertFrom-Json; $taskReady = $taskNewEndpoint.pid -eq $taskDaemon.Id } catch { }
            if ($taskReady) { break }
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $taskReady) { throw '管理员后台启动超时，请重新打开设置检查端口' }
    & (Join-Path $taskPlugin 'runtime\node.exe') (Join-Path $taskPlugin 'desktop\open.mjs')
    if ($LASTEXITCODE -ne 0) { throw '管理员后台未就绪，请重新打开设置查看启动状态' }
    Wait-Process -Id $taskDaemon.Id -ErrorAction SilentlyContinue
    return
}
& (Join-Path $taskPlugin 'runtime\node.exe') (Join-Path $taskPlugin 'desktop\open.mjs')
if ($LASTEXITCODE -ne 0) { throw '无法打开手机连接设置' }
