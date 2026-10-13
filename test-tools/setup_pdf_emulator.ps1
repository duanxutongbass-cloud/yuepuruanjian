$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$workspace = Split-Path -Parent $PSScriptRoot
$toolDirectory = Join-Path $workspace '.android-test-tools'
New-Item -ItemType Directory -Path $toolDirectory -Force | Out-Null
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$sdkRoot = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$manager = Join-Path $toolDirectory 'command-line\cmdline-tools\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $manager)) {
    [xml]$repository = (Invoke-WebRequest 'https://dl.google.com/android/repository/repository2-3.xml').Content
    $package = $repository.SelectSingleNode("//*[local-name()='remotePackage' and @path='cmdline-tools;latest']")
    $archive = $package.SelectSingleNode(".//*[local-name()='archive'][*[local-name()='host-os']='windows']/*[local-name()='complete']/*[local-name()='url']").InnerText
    $zip = Join-Path $toolDirectory 'command-line-tools.zip'
    Write-Output 'Downloading Android command-line tools.'
    & curl.exe --ssl-revoke-best-effort --fail --location --continue-at - --output $zip ('https://dl.google.com/android/repository/' + $archive)
    if ($LASTEXITCODE -ne 0) { throw 'Android command-line tools download failed.' }
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $toolDirectory 'command-line') -Force
}
Write-Output 'Installing Android 14 system image for native PDF tests.'
$standardTools = Join-Path $sdkRoot 'cmdline-tools\phase4'
if (-not (Test-Path -LiteralPath $standardTools)) {
    New-Item -ItemType Directory -Path (Join-Path $sdkRoot 'cmdline-tools') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $toolDirectory 'command-line\cmdline-tools') -Destination $standardTools -Recurse
}
& (Join-Path $PSScriptRoot 'install_pdf_system_image.ps1')
