param(
    [string]$SdkPath,
    [string]$JavaPath,
    [string]$GradlePath
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'prepare-easytier.ps1')
if (-not $SdkPath) { $SdkPath = Join-Path $taskRoot '.local\toolchain\android-sdk' }
if (-not $JavaPath) {
    $taskJdks = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot '.local\toolchain\jdk17') -Directory)
    if ($taskJdks.Count -ne 1) { throw '请用 -JavaPath 指定 JDK 17 目录' }
    $JavaPath = $taskJdks[0].FullName
}
if (-not $GradlePath) { $GradlePath = Join-Path $taskRoot '.local\toolchain\gradle-8.9\bin\gradle.bat' }
$taskSigningDirectory = Join-Path $taskRoot '.local\secrets'
New-Item -ItemType Directory -Force -Path $taskSigningDirectory | Out-Null
$taskSigningKey = Join-Path $taskSigningDirectory 'android-debug.keystore'
if (-not (Test-Path -LiteralPath $taskSigningKey)) {
    & (Join-Path $JavaPath 'bin\keytool.exe') -genkeypair -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Codex LAN Debug' -keystore $taskSigningKey -storepass android -keypass android
    if ($LASTEXITCODE -ne 0) { throw '无法创建本地调试签名' }
}
if (-not (Test-Path -LiteralPath (Join-Path $SdkPath 'platforms\android-35\android.jar'))) { throw '缺少 Android SDK platform 35' }
$env:JAVA_HOME = $JavaPath
# Some host PATH entries contain literal quotes; AGP forwards PATH into a JVM property.
# Normalize only this build process so a quoted entry cannot split the test worker command.
$env:PATH = $env:PATH.Replace('"', '')
$env:ANDROID_HOME = $SdkPath
$env:GRADLE_USER_HOME = Join-Path $taskRoot '.local\toolchain\gradle-home'
$taskAndroidBuild = Join-Path $taskRoot '.local\android-build\app'
$env:ANDROID_USER_HOME = Join-Path $taskRoot '.local\toolchain\android-user'
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
$taskTemporary = Join-Path $taskRoot '.local\toolchain\tmp'
New-Item -ItemType Directory -Force -Path $taskTemporary | Out-Null
$env:TEMP = $taskTemporary
$env:TMP = $taskTemporary
$taskProperties = 'sdk.dir=' + $SdkPath.Replace('\', '/')
$taskProperties = -join ($taskProperties.ToCharArray() | ForEach-Object { if ([int]$_ -gt 127) { '\u{0:x4}' -f [int]$_ } else { [string]$_ } })
Set-Content -LiteralPath (Join-Path $taskRoot 'android\local.properties') -Value $taskProperties -Encoding ascii
$taskResolvedBuild = [System.IO.Path]::GetFullPath($taskAndroidBuild)
if (-not $taskResolvedBuild.StartsWith([System.IO.Path]::GetFullPath($taskRoot) + '\', [StringComparison]::OrdinalIgnoreCase)) { throw '构建清理目标不在项目内' }
& $GradlePath -p (Join-Path $taskRoot 'android') "-PcodexBuildDir=$taskAndroidBuild" --no-daemon --console=plain clean compileDebugUnitTestJavaWithJavac assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Android 构建失败' }
# Run the compiled JVM tests directly: Gradle 8.9's worker argfile cannot resolve
# this Windows workspace's non-ASCII classpaths. This still runs real JUnit tests.
$taskJsonJar = Get-ChildItem (Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1\com.vaadin.external.google\android-json\0.0.20131108.vaadin1') -Recurse -Filter '*.jar' | Select-Object -First 1
$taskJunitJar = Get-ChildItem (Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1\junit\junit\4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1
$taskHamcrestJar = Get-ChildItem (Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1\org.hamcrest\hamcrest-core\1.3') -Recurse -Filter '*.jar' | Select-Object -First 1
$taskQrJar = Get-ChildItem (Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1\com.google.zxing\core\3.5.4') -Recurse -Filter '*.jar' | Select-Object -First 1
$taskClasspath = @((Join-Path $taskAndroidBuild 'intermediates\javac\debug\compileDebugJavaWithJavac\classes'), (Join-Path $taskAndroidBuild 'intermediates\javac\debugUnitTest\compileDebugUnitTestJavaWithJavac\classes'), $taskJsonJar.FullName, $taskJunitJar.FullName, $taskHamcrestJar.FullName, $taskQrJar.FullName) -join ';'
& (Join-Path $JavaPath 'bin\java.exe') -cp $taskClasspath org.junit.runner.JUnitCore dev.codexlan.remote.MobileStateTest
if ($LASTEXITCODE -ne 0) { throw 'Android 状态逻辑测试失败' }
$taskOutput = Join-Path $taskRoot '.local\releases'
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
Copy-Item -LiteralPath (Join-Path $taskAndroidBuild 'outputs\apk\debug\app-debug.apk') -Destination (Join-Path $taskOutput 'codex-lan-0.5.0.apk') -Force
Write-Output (Join-Path $taskOutput 'codex-lan-0.5.0.apk')
