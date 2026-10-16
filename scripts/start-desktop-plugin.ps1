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
