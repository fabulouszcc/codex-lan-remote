$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskCommit = '799f50d6e7bd288cd69fe6df2081c9d9502c45b4'
$taskDestination = Join-Path $taskRoot '.local\native\easytier\arm64-v8a'
New-Item -ItemType Directory -Force -Path $taskDestination | Out-Null
$taskLibraries = @{
    'libeasytier_android_jni.so' = 'ce6c9e8e7643c30fb2bb28c24e9118cacca31df90c2e6a1aa18fb5d15986de4c'
    'libeasytier_ffi.so' = '9be5f96d99e530e1817d86f9ae29645ee4dc7c0c6a156e1bc0c90900ea96d70e'
}
foreach ($taskName in $taskLibraries.Keys) {
    $taskOutput = Join-Path $taskDestination $taskName
    if (-not (Test-Path -LiteralPath $taskOutput)) {
        $taskUrl = "https://raw.githubusercontent.com/qiin2333/moonlight-vplus/$taskCommit/app/src/main/jniLibs/arm64-v8a/$taskName"
        & curl.exe --fail --location --retry 3 --silent --show-error $taskUrl --output $taskOutput
        if ($LASTEXITCODE -ne 0) { throw "下载失败：$taskName" }
    }
    if ((Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash -ne $taskLibraries[$taskName]) { throw "原生库校验失败：$taskName" }
    Write-Output "已校验：$taskName"
}
