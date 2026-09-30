# TEMPORARY diagnostic: direct log transport and the failed 59-class group.
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
function Show-ProbeState {
  Write-Output '[DEBUG-windows-process] process counts'
  Get-Process | Group-Object ProcessName | Sort-Object Count -Descending | Select-Object -First 15 Name, Count | ConvertTo-Json -Compress
  Get-CimInstance Win32_OperatingSystem | Select-Object FreePhysicalMemory, FreeVirtualMemory, TotalVirtualMemorySize, TotalVisibleMemorySize | ConvertTo-Json -Compress
  Get-WinEvent -FilterHashtable @{LogName='System'; Id=243; StartTime=(Get-Date).AddHours(-1)} -ErrorAction SilentlyContinue | Select-Object -First 5 TimeCreated, Id, Message | ConvertTo-Json -Compress
}
Show-ProbeState
.\mvnw.cmd -B -Pbackend-dev "-DreuseForks=$env:LOOPPER_PROBE_REUSE" "-Dsurefire.includesFile=$env:LOOPPER_CI_INCLUDES" test 2>&1 | Tee-Object -FilePath windows-process.log
$buildExit = $LASTEXITCODE
Write-Output "[DEBUG-windows-process] Maven exit $buildExit"
Show-ProbeState
exit $buildExit
