param([string]$NodePath)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $NodePath) { $NodePath = (Get-Command node.exe -ErrorAction Stop).Source }
$taskVersion = (& $NodePath --version).Trim()
if ($taskVersion -notmatch '^v24\.') { throw '桌面插件需要 Node.js 24 运行时' }
$taskOutput = Join-Path $taskRoot '.local\releases\codex-lan-desktop-0.3.4-preview'
$taskPlugin = Join-Path $taskOutput 'plugins\codex-lan-desktop'
New-Item -ItemType Directory -Force -Path $taskPlugin | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRoot 'plugins\codex-lan-desktop\.codex-plugin') -Destination $taskPlugin -Recurse -Force
Copy-Item -LiteralPath (Join-Path $taskRoot 'plugins\codex-lan-desktop\.mcp.json') -Destination $taskPlugin -Force
Copy-Item -LiteralPath (Join-Path $taskRoot 'plugins\codex-lan-desktop\launch.cmd') -Destination $taskPlugin -Force
foreach ($taskDirectory in @('desktop','server')) { Copy-Item -LiteralPath (Join-Path $taskRoot $taskDirectory) -Destination $taskPlugin -Recurse -Force }
Copy-Item -LiteralPath (Join-Path $taskRoot 'node_modules\qrcode-generator\dist\qrcode.js') -Destination (Join-Path $taskPlugin 'desktop\web\qrcode.js') -Force
$taskRuntime = Join-Path $taskPlugin 'runtime'
$taskVendor = Join-Path $taskPlugin 'vendor\easytier'
$taskScripts = Join-Path $taskPlugin 'scripts'
$taskLicenses = Join-Path $taskPlugin 'licenses'
New-Item -ItemType Directory -Force -Path $taskRuntime,$taskVendor,$taskScripts,$taskLicenses | Out-Null
Copy-Item -LiteralPath $NodePath -Destination (Join-Path $taskRuntime 'node.exe') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-desktop-plugin.ps1') -Destination $taskScripts -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'prepare-desktop-native.ps1') -Destination $taskScripts -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install-settings-shortcuts.ps1') -Destination $taskScripts -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install-desktop-plugin.ps1') -Destination $taskOutput -Force
Copy-Item -LiteralPath (Join-Path $taskRoot 'docs\电脑端插件使用指南.md') -Destination (Join-Path $taskOutput 'README.md') -Force
$taskMarketplace = Join-Path $taskOutput '.agents\plugins'
New-Item -ItemType Directory -Force -Path $taskMarketplace | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRoot 'desktop\marketplace.json') -Destination (Join-Path $taskMarketplace 'marketplace.json') -Force
$taskDependencies = Join-Path $taskRoot '.local\toolchain\desktop-dependencies'
New-Item -ItemType Directory -Force -Path $taskDependencies | Out-Null
function Get-TaskDownload([string]$Url, [string]$Target) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Target) | Out-Null
    if (-not (Test-Path -LiteralPath $Target)) {
        Invoke-WebRequest -Uri $Url -OutFile ($Target + '.part')
        Move-Item -LiteralPath ($Target + '.part') -Destination $Target
    }
}
$taskEtArchive = Join-Path $taskRoot '.local\toolchain\easytier-desktop-2.6.4\easytier-windows-x86_64-v2.6.4.zip'
Get-TaskDownload 'https://github.com/EasyTier/EasyTier/releases/download/v2.6.4/easytier-windows-x86_64-v2.6.4.zip' $taskEtArchive
if ((Get-FileHash -LiteralPath $taskEtArchive -Algorithm SHA256).Hash -ne '27AF91E270E554709B048BD32327FEFD2DFCE5062AE1E8701AF7550C6F525F84') { throw 'EasyTier 固定版本摘要不匹配' }
$taskEtUnpack = Join-Path $taskDependencies 'easytier-2.6.4'
Expand-Archive -LiteralPath $taskEtArchive -DestinationPath $taskEtUnpack -Force
foreach ($taskName in @('easytier-core.exe','easytier-cli.exe','wintun.dll','Packet.dll')) {
    $taskFile = Get-ChildItem -LiteralPath $taskEtUnpack -Recurse -Filter $taskName | Select-Object -First 1
    if (-not $taskFile) { throw ('EasyTier 缺少 ' + $taskName) }
    Copy-Item -LiteralPath $taskFile.FullName -Destination $taskVendor -Force
}
Get-TaskDownload ('https://raw.githubusercontent.com/nodejs/node/' + $taskVersion + '/LICENSE') (Join-Path $taskDependencies ('Node-' + $taskVersion + '-LICENSE.txt'))
Get-TaskDownload 'https://raw.githubusercontent.com/EasyTier/EasyTier/v2.6.4/LICENSE' (Join-Path $taskDependencies 'EasyTier-2.6.4-LICENSE.txt')
$taskQrLicense = @'
MIT License
Copyright (c) 2009 Kazuhiko Arase

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
'@
Set-Content -LiteralPath (Join-Path $taskDependencies 'qrcode-LICENSE.txt') -Value $taskQrLicense -Encoding utf8
Get-TaskDownload 'https://www.wintun.net/builds/wintun-0.14.1.zip' (Join-Path $taskDependencies 'wintun-0.14.1.zip')
Expand-Archive -LiteralPath (Join-Path $taskDependencies 'wintun-0.14.1.zip') -DestinationPath (Join-Path $taskDependencies 'wintun') -Force
$taskWintunLicense = Get-ChildItem -LiteralPath (Join-Path $taskDependencies 'wintun') -Recurse -Filter LICENSE.txt | Select-Object -First 1
if (-not $taskWintunLicense) { throw '缺少 Wintun 许可证' }
Copy-Item -LiteralPath $taskWintunLicense.FullName -Destination (Join-Path $taskLicenses 'Wintun-LICENSE.txt') -Force
$taskOfficialWintun = Join-Path $taskDependencies 'wintun\wintun\bin\amd64\wintun.dll'
if ((Get-FileHash -LiteralPath $taskOfficialWintun).Hash -ne (Get-FileHash -LiteralPath (Join-Path $taskVendor 'wintun.dll')).Hash) { throw 'EasyTier Wintun 与官方 0.14.1 不匹配，需要核对对应源码版本' }
foreach ($taskName in @(('Node-' + $taskVersion + '-LICENSE.txt'),'EasyTier-2.6.4-LICENSE.txt','qrcode-LICENSE.txt')) { Copy-Item -LiteralPath (Join-Path $taskDependencies $taskName) -Destination $taskLicenses -Force }
$taskNotice = @"
Node.js ${taskVersion}: https://github.com/nodejs/node/tree/$taskVersion
EasyTier 2.6.4-8428a89d, unmodified Windows x86_64 release executables:
https://github.com/EasyTier/EasyTier/releases/tag/v2.6.4
Corresponding source: https://github.com/EasyTier/EasyTier/archive/refs/tags/v2.6.4.tar.gz
Wintun 0.14.1: https://www.wintun.net/ ; source: https://git.zx2c4.com/wintun/
qrcode-generator 2.0.4 source: https://registry.npmjs.org/qrcode-generator/-/qrcode-generator-2.0.4.tgz
These programs remain separately replaceable. No EasyTier GUI or WinDivert is bundled.
Packet.dll is excluded from the distributable ZIP. The installer obtains it from
the official EasyTier archive for the local user's installation (see prepare-desktop-native.ps1).
Project code is a local preview; third-party components retain their respective licenses.
"@
Set-Content -LiteralPath (Join-Path $taskLicenses 'SOURCES.txt') -Value $taskNotice -Encoding utf8
$taskHashes = @{}
foreach ($taskFile in @(Get-ChildItem -LiteralPath $taskRuntime,$taskVendor -File)) { $taskHashes[$taskFile.Name] = (Get-FileHash -LiteralPath $taskFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
$taskHashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskPlugin 'dependency-hashes.json') -Encoding utf8
$taskZip = Join-Path $taskRoot '.local\releases\codex-lan-desktop-0.3.4-preview.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZipStream = [IO.File]::Open($taskZip, [IO.FileMode]::Create)
$taskZipWriter = [IO.Compression.ZipArchive]::new($taskZipStream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($taskFile in @(Get-ChildItem -LiteralPath $taskOutput -File -Recurse -Force)) {
        if ($taskFile.Name -eq 'Packet.dll') { continue }
        $taskRelative = [IO.Path]::GetRelativePath($taskOutput, $taskFile.FullName).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZipWriter, $taskFile.FullName, $taskRelative, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $taskZipWriter.Dispose(); $taskZipStream.Dispose() }
Write-Output $taskOutput
Write-Output $taskZip
