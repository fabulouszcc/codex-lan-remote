param(
    [Parameter(Mandatory=$true)][string]$SdkPath,
    [Parameter(Mandatory=$true)][string]$JavaPath,
    [Parameter(Mandatory=$true)][string]$Adb,
    [ValidateSet('dev.codexlan.remote','dev.codexlan.remote.memoryfix')][string]$Package='dev.codexlan.remote'
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$runId=[Guid]::NewGuid().ToString('N')
$output=Join-Path $root ('.local\memory-device-test\'+$runId)
$classes=Join-Path $output 'classes';$dex=Join-Path $output 'dex'
New-Item -ItemType Directory -Force $classes,$dex | Out-Null
$androidJar=Join-Path $SdkPath 'platforms\android-35\android.jar'
$source=Join-Path $root 'android\app\src\main\java'
$files=@('MemoryJson.java','EncryptedHistory.java','HistoryCache.java','SecureSession.java') | ForEach-Object {Join-Path $source ('dev\codexlan\remote\'+$_)}
$files+=Join-Path $PSScriptRoot 'android\tests\MemoryDeviceTest.java'
& (Join-Path $JavaPath 'bin\javac.exe') -encoding UTF-8 -source 8 -target 8 -cp $androidJar -sourcepath $source -d $classes $files
if($LASTEXITCODE -ne 0){throw 'Regression helper compilation failed'}
$bytecode=@(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
& (Join-Path $JavaPath 'bin\java.exe') -cp (Join-Path $SdkPath 'build-tools\35.0.0\lib\d8.jar') com.android.tools.r8.D8 --min-api 28 --lib $androidJar --output $dex $bytecode
if($LASTEXITCODE -ne 0){throw 'Regression DEX compilation failed'}
$temporary='/data/local/tmp/codex-memory-regression-'+$runId+'.dex'
$helper='cache/codex-memory-regression-'+$runId+'.dex'
$cache='cache/codex-memory-regression-'+$runId
try{
    & $Adb -d push (Join-Path $dex 'classes.dex') $temporary
    if($LASTEXITCODE -ne 0){throw 'Regression helper transfer failed'}
    & $Adb -d shell run-as $Package cp $temporary $helper
    if($LASTEXITCODE -ne 0){throw 'Install a debuggable build before running the device test'}
    & $Adb -d shell run-as $Package chmod 444 $helper
    if($LASTEXITCODE -ne 0){throw 'Cannot protect regression DEX'}
    & $Adb -d shell -n run-as $Package env ('CLASSPATH=/data/user/0/'+$Package+'/'+$helper) app_process /system/bin dev.codexlan.remote.MemoryDeviceTest ('/data/user/0/'+$Package+'/'+$cache)
    if($LASTEXITCODE -ne 0){throw 'Android memory regression failed'}
}finally{
    # Only this run's UUID-named cache and helper; never history, preferences or pairing keys.
    & $Adb -d shell run-as $Package rm -f $helper
    & $Adb -d shell run-as $Package rm -rf $cache
    & $Adb -d shell rm -f $temporary
}
