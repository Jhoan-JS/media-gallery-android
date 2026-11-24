param(
    [string] $Gradle = 'gradle',
    [switch] $SkipEngine,
    [switch] $TestsOnly
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (!(Test-Path "$env:JAVA_HOME/bin/keytool.exe")) { throw 'Set JAVA_HOME to a JDK 21 installation' }
if (!$TestsOnly) {
    if (!$SkipEngine) { & "$root/telegram/build-engine.ps1" }
    $debugKey = "$root/.qa/debug.keystore"
    if (!(Test-Path -LiteralPath $debugKey)) {
        New-Item -ItemType Directory -Force (Split-Path $debugKey) | Out-Null
        & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore $debugKey -storepass android -keypass android -alias androiddebugkey -dname 'CN=Disposable Android Debug,O=Local build,C=US' -keyalg RSA -keysize 2048 -validity 3650
        if ($LASTEXITCODE) { throw 'Disposable debug key generation failed' }
    }
}
$tasks = @(':app:testDebugUnitTest', ':telegram:testDebugUnitTest', ':webconnector:testDebugUnitTest')
if (!$TestsOnly) {
    $tasks += @(':app:assembleDebug', ':telegram:assembleDebug', ':app:assembleRelease', ':telegram:assembleRelease', ':app:lintRelease', ':telegram:lintRelease', ':webconnector:assembleDebug', ':webconnector:assembleRelease', ':webconnector:lintRelease')
}
& $Gradle -p $root @tasks --console plain
if ($LASTEXITCODE) { throw 'Android checks failed' }
