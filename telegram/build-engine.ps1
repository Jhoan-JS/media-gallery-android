param(
    [string] $GoHome = $env:TATO_GO_HOME,
    [string] $AndroidSdk = $env:ANDROID_HOME,
    [string] $JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $project '.qa/tg-tools'
$goVersion = '1.27.4'
$goHash = '178ab98950ac69ab25069307dc6ade79f3816e414cce95b4da166a115cde1e7e'
$mobileVersion = 'v0.0.0-20261011034528-197770444867'
if (!$AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$androidSdk = $AndroidSdk
$ndk = Join-Path $androidSdk 'ndk/28.2.13676358'
if (!$JavaHome) { throw 'Set JAVA_HOME to a JDK 21 installation' }
$java = $JavaHome
$goBin = if ($GoHome) { Join-Path $GoHome 'bin' } else { Join-Path $toolRoot 'go/bin' }
$engine = Join-Path $PSScriptRoot 'engine'
$out = Join-Path $engine 'dist/tgengine.aar'
New-Item -ItemType Directory -Force $toolRoot, (Join-Path $toolRoot 'tmp'), (Split-Path $out -Parent) | Out-Null

if (!(Test-Path (Join-Path $goBin 'go.exe'))) {
    if ($GoHome) { throw 'TATO_GO_HOME does not contain a Go compiler' }
    $archive = Join-Path $toolRoot "go$goVersion.windows-amd64.zip"
    if (!(Test-Path $archive)) { Invoke-WebRequest "https://go.dev/dl/go$goVersion.windows-amd64.zip" -OutFile $archive }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $goHash) { throw 'Go SDK checksum mismatch' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($archive, $toolRoot)
}
if (!(Test-Path "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe")) { throw 'Install Android NDK 28.2.13676358 first' }
if (!(Test-Path "$java/bin/javac.exe")) { throw 'Android Studio JBR is required' }
$names = @('PATH','GOPATH','GOCACHE','GOTOOLCHAIN','GOTMPDIR','GOFLAGS','GOMAXPROCS','JAVA_HOME','ANDROID_HOME','ANDROID_NDK_HOME')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:GOPATH = Join-Path $toolRoot 'gopath'
    $env:GOCACHE = Join-Path $toolRoot 'cache'
    $env:GOTOOLCHAIN = 'local'
    $env:GOTMPDIR = Join-Path $toolRoot 'tmp'
    $env:GOFLAGS = '-p=2'
    $env:GOMAXPROCS = '4'
    $env:JAVA_HOME = $java
    $env:ANDROID_HOME = $androidSdk
    $env:ANDROID_NDK_HOME = $ndk
    $env:PATH = "$goBin;$env:GOPATH/bin;$java/bin;" + $saved['PATH']
    if ((& go version) -notmatch "go$([regex]::Escape($goVersion)) windows/amd64") { throw 'Unexpected Go SDK version' }
    Push-Location $engine
    try {
        & go install "golang.org/x/mobile/cmd/gomobile@$mobileVersion"
        if ($LASTEXITCODE) { throw 'gomobile install failed' }
        & go install "golang.org/x/mobile/cmd/gobind@$mobileVersion"
        if ($LASTEXITCODE) { throw 'gobind install failed' }
        & go mod download
        if ($LASTEXITCODE) { throw 'Dependency download failed' }
        & go mod verify
        if ($LASTEXITCODE) { throw 'Module verification failed' }
        & go test ./...
        if ($LASTEXITCODE) { throw 'Engine tests failed' }
        & go build ./...
        if ($LASTEXITCODE) { throw 'Host compilation failed' }
        & gomobile init
        if ($LASTEXITCODE) { throw 'gomobile init failed' }
        & gomobile bind '-target=android/arm64,android/amd64' -androidapi=31 -o $out '-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384' .
        if ($LASTEXITCODE) { throw 'Android engine build failed' }
    } finally { Pop-Location }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $aar = [IO.Compression.ZipFile]::OpenRead($out)
    try {
        foreach ($abi in @('arm64-v8a','x86_64')) {
            $entry = $aar.GetEntry("jni/$abi/libgojni.so")
            if (!$entry) { throw "Missing engine ABI $abi" }
            $inspect = Join-Path $engine "dist/inspect/$abi"
            New-Item -ItemType Directory -Force $inspect | Out-Null
            $library = Join-Path $inspect 'libgojni.so'
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $library, $true)
            $headers = & "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe" --wide --program-headers $library
            if ($LASTEXITCODE) { throw "Cannot inspect $abi engine" }
            $loads = @($headers | Where-Object { $_ -match '^\s*LOAD\s' })
            if ($loads.Count -lt 1) { throw "No ELF load segments for $abi" }
            foreach ($line in $loads) {
                $alignment = ($line.Trim() -split '\s+')[-1]
                if ([Convert]::ToInt64($alignment.Replace('0x',''),16) -lt 16384) { throw "$abi lacks Android 16 KiB ELF alignment" }
            }
            Write-Output "$abi ELF LOAD alignment: 16 KiB or greater"
        }
    } finally { $aar.Dispose() }
    Get-FileHash -LiteralPath $out -Algorithm SHA256
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
