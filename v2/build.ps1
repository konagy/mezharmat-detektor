$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Join-Path $root '.tools\jdk\jdk-21.0.12.1+1'
$maven = Join-Path $root '.tools\maven\apache-maven-3.9.16\bin\mvn.cmd'
if (!(Test-Path "$env:JAVA_HOME\bin\java.exe") -or !(Test-Path $maven)) { throw 'Run v2\setup.ps1 first.' }
$vendor = Join-Path $PSScriptRoot 'vendor\AnimalTracker_.jar'
if (!(Test-Path $vendor) -or (Get-FileHash $vendor -Algorithm SHA256).Hash -ne '286E56CC1D582C881EF6364C1A571195C400B176666FA24C430A6EF28A93E64F') { throw 'AnimalTracker is missing or has an unexpected checksum. Run setup.ps1.' }
& $maven -q "-Dmaven.repo.local=$root\.tools\m2" org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file "-Dfile=$vendor" '-DgroupId=hu.elte' '-DartifactId=animaltracker' '-Dversion=2016-pinned' '-Dpackaging=jar' '-DgeneratePom=true'
if ($LASTEXITCODE -ne 0) { throw 'Could not register the verified AnimalTracker dependency.' }
& $maven --no-transfer-progress "-Dmaven.repo.local=$root\.tools\m2" -f "$PSScriptRoot\pom.xml" package @args
if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
