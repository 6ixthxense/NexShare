# Downloads a private JDK 17 and the Android SDK pieces needed by build.ps1 into .tools.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Tools = Join-Path $PSScriptRoot '.tools'
$Dl = Join-Path $Tools 'downloads'
New-Item -ItemType Directory -Force $Tools, $Dl | Out-Null

if (-not (Test-Path "$Tools\jdk")) {
    Write-Host '== JDK 17 (Temurin)'
    Invoke-WebRequest 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile "$Dl\jdk.zip"
    Expand-Archive "$Dl\jdk.zip" "$Tools\jdk"
}
$Jdk = Get-ChildItem "$Tools\jdk" -Directory | Select-Object -First 1
$env:JAVA_HOME = $Jdk.FullName
$env:Path = "$($Jdk.FullName)\bin;$env:Path"

$Sdk = Join-Path $Tools 'sdk'
if (-not (Test-Path "$Sdk\cmdline-tools\latest")) {
    Write-Host '== Android command-line tools'
    Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile "$Dl\cmdline.zip"
    Expand-Archive "$Dl\cmdline.zip" "$Dl\cmdline" -Force
    New-Item -ItemType Directory -Force "$Sdk\cmdline-tools" | Out-Null
    Move-Item "$Dl\cmdline\cmdline-tools" "$Sdk\cmdline-tools\latest"
}

$libs = Join-Path $PSScriptRoot 'libs'
New-Item -ItemType Directory -Force $libs | Out-Null
$ltVer = '2.1.0-39'
$ltBase = "https://repo1.maven.org/maven2/org/libtorrent4j"
foreach ($art in @('libtorrent4j', 'libtorrent4j-android-arm64', 'libtorrent4j-android-arm')) {
    $jar = Join-Path $libs "$art-$ltVer.jar"
    if (-not (Test-Path $jar)) {
        Write-Host "== libtorrent4j: $art"
        Invoke-WebRequest "$ltBase/$art/$ltVer/$art-$ltVer.jar" -OutFile $jar
    }
}

foreach ($cf in @('arm64', 'arm')) {
    $bin = Join-Path $libs "cloudflared-linux-$cf"
    if (-not (Test-Path $bin)) {
        Write-Host "== cloudflared: $cf"
        Invoke-WebRequest "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$cf" -OutFile $bin
    }
}

Write-Host '== platform 34 + build-tools 34 + 36 (accepting SDK licenses)'
$sm = "$Sdk\cmdline-tools\latest\bin\sdkmanager.bat"
('y`n' * 20) -replace '`n', "`n" | & $sm --sdk_root=$Sdk --licenses | Out-Null
# build-tools 36: 34's d8 crashes on libtorrent4j. 34 kept for other tools.
& $sm --sdk_root=$Sdk 'platforms;android-34' 'build-tools;34.0.0' 'build-tools;36.0.0'
if ($LASTEXITCODE -ne 0) { throw 'sdkmanager failed' }
Write-Host '== setup done — now run: .\build.ps1'
