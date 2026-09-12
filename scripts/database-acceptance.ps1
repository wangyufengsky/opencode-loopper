param([Parameter(Mandatory=$true)][string]$Jar,
      [Parameter(Mandatory=$true)][string]$DataDirectory,
      [Parameter(Mandatory=$true)][string]$Probe,
      [Parameter(Mandatory=$true)][string]$Report)
$ErrorActionPreference = 'Stop'
& java '-Dloader.main=io.opencode.loopper.service.assist.DatabaseOfflineAcceptance' '-cp' $Jar 'org.springframework.boot.loader.launch.PropertiesLauncher' $DataDirectory $Probe $Report
exit $LASTEXITCODE
