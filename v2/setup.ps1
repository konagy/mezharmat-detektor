param([switch]$IncludeFiji)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$toolDir = Join-Path $root '.tools'
New-Item -ItemType Directory -Force -Path $toolDir, "$PSScriptRoot\vendor" | Out-Null
function Get-VerifiedArtifact([string]$Url, [string]$Destination, [string]$Algorithm, [string]$Expected) {
    if (!(Test-Path -LiteralPath $Destination)) {
        Write-Host "Downloading $Url"
        Invoke-WebRequest -Uri $Url -OutFile $Destination
    }
    if ((Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash -ne $Expected) {
        throw "Checksum mismatch: $Destination. Nothing will be installed. Inspect the download; do not bypass this check."
    }
}
Get-VerifiedArtifact 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip' "$toolDir\jdk.zip" 'SHA256' 'F9D6E191AB098C0D416E7D588A24420A8621CD2F4720DAB2459B8B7B2D2D8B4E'
if (!(Test-Path "$toolDir\jdk\jdk-21.0.12.1+1\bin\java.exe")) { Expand-Archive -LiteralPath "$toolDir\jdk.zip" -DestinationPath "$toolDir\jdk" }
Get-VerifiedArtifact 'https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip' "$toolDir\maven.zip" 'SHA512' 'ED41650D42485CFC243FAD22158CAF9CBB5DC408CE7A09DDB94DD42A019DE929CA43065BFA450612CF12BF78B5CAFA3884B96C090DE326FF590448C933454AF3'
if (!(Test-Path "$toolDir\maven\apache-maven-3.9.16\bin\mvn.cmd")) { Expand-Archive -LiteralPath "$toolDir\maven.zip" -DestinationPath "$toolDir\maven" }
Get-VerifiedArtifact 'https://animaltracker.elte.hu/AnimalTracker_.jar' "$PSScriptRoot\vendor\AnimalTracker_.jar" 'SHA256' '286E56CC1D582C881EF6364C1A571195C400B176666FA24C430A6EF28A93E64F'
if ($IncludeFiji) {
    # The upstream latest URL is mutable. This pin accepts only the tested 2026-10-07 archive.
    Get-VerifiedArtifact 'https://downloads.imagej.net/fiji/latest/fiji-latest-win64-jdk.zip' "$toolDir\fiji.zip" 'SHA256' '8E33A11C7E79EB02C055BCEE40DE806D16E59F0B42E58C4689BD23EB47D990BB'
    if (!(Test-Path "$toolDir\fiji\Fiji\fiji-windows-x64.exe")) { Expand-Archive -LiteralPath "$toolDir\fiji.zip" -DestinationPath "$toolDir\fiji" }
    $plugin = "$toolDir\fiji\Fiji\plugins\AnimalTracker_.jar"
    if ((Test-Path $plugin) -and (Get-FileHash $plugin -Algorithm SHA256).Hash -ne '286E56CC1D582C881EF6364C1A571195C400B176666FA24C430A6EF28A93E64F') {
        throw 'A different AnimalTracker plugin is already installed. Preserve it and resolve the version explicitly.'
    }
    Copy-Item -LiteralPath "$PSScriptRoot\vendor\AnimalTracker_.jar" -Destination $plugin
}
Write-Host 'Local dependencies are ready. Run: powershell -ExecutionPolicy Bypass -File v2\build.ps1'
