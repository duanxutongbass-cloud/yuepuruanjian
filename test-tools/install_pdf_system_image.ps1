$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$workspace = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $workspace '.android-test-tools'
$sdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_AVD_HOME = Join-Path $tools 'avd'
$zip = Join-Path $tools 'android-34-system-image.zip'
[xml]$repository = (Invoke-WebRequest 'https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml').Content
$package = $repository.SelectSingleNode("//*[local-name()='remotePackage' and @path='system-images;android-34;default;x86_64']")
$archive = $package.SelectSingleNode(".//*[local-name()='complete'][*[local-name()='url']='x86_64-34_r04.zip']")
$expectedHash = $archive.SelectSingleNode("*[local-name()='checksum']").InnerText
if (-not (Test-Path -LiteralPath $zip) -or (Get-Item -LiteralPath $zip).Length -ne [long]$archive.SelectSingleNode("*[local-name()='size']").InnerText) {
    & curl.exe --ssl-revoke-best-effort --fail --location --continue-at - --output $zip 'https://dl.google.com/android/repository/sys-img/android/x86_64-34_r04.zip'
    if ($LASTEXITCODE -ne 0) { throw 'System image download failed.' }
}
if ((Get-FileHash -LiteralPath $zip -Algorithm SHA1).Hash -ne $expectedHash) { throw 'Official archive checksum mismatch.' }
$destination = Join-Path $sdk 'system-images\android-34\default'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
Write-Output 'Extracting verified official Android system image.'
if (-not (Test-Path -LiteralPath (Join-Path $destination 'x86_64\system.img'))) { Expand-Archive -LiteralPath $zip -DestinationPath $destination -Force }
$metadataPath = Join-Path $destination 'x86_64\package.xml'
if (-not (Test-Path -LiteralPath $metadataPath) -or ([xml](Get-Content -LiteralPath $metadataPath)).DocumentElement.NamespaceURI -ne 'http://schemas.android.com/repository/android/common/02') {
    $metadata = New-Object System.Xml.XmlDocument
    $root = $metadata.CreateElement('local', 'repository', 'http://schemas.android.com/repository/android/common/02')
    foreach ($attribute in $repository.DocumentElement.Attributes) {
        if ($attribute.Name.StartsWith('xmlns:')) { $root.SetAttribute($attribute.Name, $attribute.Value) }
    }
    $metadata.AppendChild($root) | Out-Null
    foreach ($license in $repository.DocumentElement.SelectNodes("*[local-name()='license']")) {
        $root.AppendChild($metadata.ImportNode($license, $true)) | Out-Null
    }
    $local = $metadata.CreateElement('localPackage')
    $local.SetAttribute('path', $package.GetAttribute('path'))
    foreach ($child in $package.ChildNodes) {
        if ($child.LocalName -notin @('archives', 'channelRef')) { $local.AppendChild($metadata.ImportNode($child, $true)) | Out-Null }
    }
    $root.AppendChild($local) | Out-Null
    $metadata.Save($metadataPath)
}
New-Item -ItemType Directory -Force -Path $env:ANDROID_AVD_HOME | Out-Null
'no' | & (Join-Path $sdk 'cmdline-tools\phase4\bin\avdmanager.bat') create avd --name Phase4Pdf --package 'system-images;android-34;default;x86_64' --force
if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed.' }
Write-Output 'Phase4Pdf is ready for native PDF tests.'
