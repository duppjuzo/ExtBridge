param(
    [ValidateSet("Release", "Debug")]
    [string]$Configuration = "Release"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $sdk) { throw "Android SDK not found; set ANDROID_HOME." }
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
    throw "Set JAVA_HOME to JDK 21 or newer."
}
$bt = Join-Path $sdk "build-tools\37.0.0"
$androidJar = Join-Path $sdk "platforms\android-37.0\android.jar"
if (-not (Test-Path $androidJar) -or -not (Test-Path "$bt\d8.bat")) {
    throw "Install SDK platforms;android-37.0 and build-tools;37.0.0."
}
$env:ANDROID_HOME = $sdk
$classes = Join-Path $root "build\bridge_classes"
$out = Join-Path $root "build\out"
$assets = Join-Path $root "helper\assets"
# Only delete generated class files, never the user's working tree or signing keys.
if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
New-Item -ItemType Directory -Force $classes,$out,$assets | Out-Null
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 --release 11 -cp $androidJar -d $classes "$root\bridge\src\com\extbridge\daemon\ExtBridge.java"
if ($LASTEXITCODE -ne 0) { throw "Lite daemon javac failed: $LASTEXITCODE" }
$classFiles = @(Get-ChildItem $classes -Recurse -Filter "*.class" | ForEach-Object { $_.FullName })
if (Test-Path "$out\extbridge.jar") { Remove-Item "$out\extbridge.jar" -Force }
& "$bt\d8.bat" --min-api 30 --output "$out\extbridge.jar" @classFiles
if ($LASTEXITCODE -ne 0) { throw "Lite daemon d8 failed: $LASTEXITCODE" }
# Normalize d8's ZIP headers into a canonical archive so `unzip -p`/`unzip -tq`
# keep working on strict ROMs (MIUI 分身 / 未开启安全设置) that reject non-standard
# local-header layouts. Repack with stored entries + Deflate, preserving entry order.
$zipSrc = Join-Path $out "extbridge.jar"
$zipTmp = "$zipSrc.norm"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$inZip = [System.IO.Compression.ZipFile]::OpenRead($zipSrc)
try {
    $outZip = [System.IO.Compression.ZipFile]::Open($zipTmp, 'Create')
    try {
        foreach ($entry in $inZip.Entries) {
            $outEntry = $outZip.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
            $inStream = $entry.Open()
            $outStream = $outEntry.Open()
            try { $inStream.CopyTo($outStream) } finally {
                $outStream.Dispose(); $inStream.Dispose()
            }
        }
    } finally { $outZip.Dispose() }
} finally { $inZip.Dispose() }
Move-Item -Force $zipTmp $zipSrc
Copy-Item "$out\extbridge.jar" "$assets\extbridge.jar" -Force
Push-Location $root
try {
    $gradleArgs = @((":helper:assemble" + $Configuration), "--no-daemon", "--console=plain")
    & "$root\gradlew.bat" @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
$signed = $false
if ($Configuration -eq "Debug") {
    $apk = Join-Path $out "exthelper.apk"
    Copy-Item (Join-Path $root "helper\build\outputs\apk\debug\helper-debug.apk") $apk -Force
    $signed = $true
} else {
    $releaseApk = Join-Path $root "helper\build\outputs\apk\release\helper-release-unsigned.apk"
    $ksPath = $env:EXTBRIDGE_KEYSTORE
    if ($ksPath -and -not (Test-Path $ksPath)) { throw "EXTBRIDGE_KEYSTORE does not exist." }
    if (-not $ksPath) {
        $ksPath = @((Join-Path $root "exthelper.p12"), (Join-Path $root "build\exthelper.p12")) |
            Where-Object { Test-Path $_ } | Select-Object -First 1
    }
    if ($ksPath) {
        if (-not $env:EXTBRIDGE_KEYSTORE_PASS) { throw "Set EXTBRIDGE_KEYSTORE_PASS to sign Release." }
        $apk = Join-Path $out "exthelper.apk"
        & "$bt\apksigner.bat" sign --ks $ksPath --ks-pass env:EXTBRIDGE_KEYSTORE_PASS --out $apk $releaseApk
        if ($LASTEXITCODE -ne 0) { throw "Release signing failed: $LASTEXITCODE" }
        $signed = $true
    } else {
        $apk = Join-Path $out "exthelper-unsigned.apk"
        Copy-Item $releaseApk $apk -Force
        Write-Warning "Release APK is unsigned; provide EXTBRIDGE_KEYSTORE and EXTBRIDGE_KEYSTORE_PASS to sign it."
    }
}
if ($signed) {
    & "$bt\apksigner.bat" verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed." }
}
Write-Host "$Configuration APK: $apk"
Get-FileHash $apk -Algorithm SHA256
