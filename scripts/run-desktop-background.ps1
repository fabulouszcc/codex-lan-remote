param([string]$DataDirectory)
$ErrorActionPreference = 'Stop'
$taskPlugin = Split-Path -Parent $PSScriptRoot
if (-not $DataDirectory) { $DataDirectory = Join-Path $env:LOCALAPPDATA 'CodexLAN' }
$env:CODEX_LAN_DATA_DIR = $DataDirectory
$taskStateFile = Join-Path $DataDirectory 'background-status.local.json'
[IO.File]::WriteAllText($taskStateFile, '{"phase":"launching"}', [Text.UTF8Encoding]::new($false))
$taskProcess = Start-Process -FilePath (Join-Path $taskPlugin 'runtime\node.exe') -ArgumentList @(('"' + (Join-Path $taskPlugin 'desktop\background.mjs') + '"'), '--data', ('"' + $DataDirectory + '"')) -WindowStyle Hidden -Wait -PassThru
[IO.File]::WriteAllText($taskStateFile, (@{phase='exited';code=[int]$taskProcess.ExitCode} | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
exit $taskProcess.ExitCode
