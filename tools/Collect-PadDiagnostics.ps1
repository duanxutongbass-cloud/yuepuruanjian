param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Label = 'pad',
    [ValidateRange(1, 120)][int]$Samples = 1,
    [ValidateRange(1, 60)][int]$IntervalSeconds = 5,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root "app\build\reports\device-acceptance\$Label-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
if (!(Test-Path -LiteralPath $Adb -PathType Leaf)) { throw 'ADB not found. Supply -Adb explicitly.' }
function Read-Adb([string[]]$Arguments, [switch]$AllowAbsentPackage) {
    $result = & $Adb -s $Serial @Arguments 2>&1
    if ($AllowAbsentPackage -and $LASTEXITCODE -eq 1 -and [string]::IsNullOrWhiteSpace(($result -join "`n"))) { return '' }
    if ($LASTEXITCODE -ne 0) { throw "Device command failed: $($Arguments -join ' '): $result" }
    return ($result -join "`n").Trim()
}
if ((Read-Adb @('get-state')) -ne 'device') { throw 'Device is not authorized/connected.' }
$package = 'com.ensemble.scorelibrary'
$info = [ordered]@{
    capturedAt = (Get-Date).ToString('o')
    serial = $Serial
    model = Read-Adb @('shell', 'getprop', 'ro.product.model')
    manufacturer = Read-Adb @('shell', 'getprop', 'ro.product.manufacturer')
    android = Read-Adb @('shell', 'getprop', 'ro.build.version.release')
    api = Read-Adb @('shell', 'getprop', 'ro.build.version.sdk')
    emulatorFlag = Read-Adb @('shell', 'getprop', 'ro.kernel.qemu')
    screenSize = Read-Adb @('shell', 'wm', 'size')
    screenDensity = Read-Adb @('shell', 'wm', 'density')
    packagePath = Read-Adb -Arguments @('shell', 'pm', 'path', $package) -AllowAbsentPackage
    sampleCount = $Samples
    intervalSeconds = $IntervalSeconds
    policy = 'Read-only diagnostics. No install, uninstall, data clearing, settings changes, screenshots or logcat.'
}
New-Item -ItemType Directory -Path $output -Force | Out-Null
$info | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'device.json') -Encoding utf8
if ([string]::IsNullOrWhiteSpace($info.packagePath)) {
    Write-Output "Device metadata collected; app not installed: $output"
    return
}
Read-Adb @('shell', 'dumpsys', 'package', $package) | Set-Content -LiteralPath (Join-Path $output 'package.txt') -Encoding utf8
for ($i = 0; $i -lt $Samples; $i++) {
    $prefix = 'sample-{0:D3}' -f $i
    (Get-Date).ToString('o') | Set-Content -LiteralPath (Join-Path $output "$prefix-time.txt") -Encoding utf8
    Read-Adb @('shell', 'dumpsys', 'meminfo', $package) | Set-Content -LiteralPath (Join-Path $output "$prefix-memory.txt") -Encoding utf8
    Read-Adb @('shell', 'dumpsys', 'gfxinfo', $package, 'framestats') | Set-Content -LiteralPath (Join-Path $output "$prefix-frames.txt") -Encoding utf8
    if ($i -lt $Samples - 1) { Start-Sleep -Seconds $IntervalSeconds }
}
Write-Output "Read-only diagnostics collected: $output"
