# Windows CI keeps the complete Maven output while reporting bounded progress independently.
$build = Start-Job -ArgumentList $env:GITHUB_WORKSPACE -ScriptBlock {
  param($workspace)
  Set-Location $workspace
  $ErrorActionPreference = 'Stop'
  $PSNativeCommandUseErrorActionPreference = $false
  .\mvnw.cmd -B "-DreuseForks=false" clean verify *> windows-verify.log
  [pscustomobject]@{ ExitCode = $LASTEXITCODE }
}
$dumpCaptured = $false
try {
  while ($build.State -in @('Running', 'NotStarted')) {
    Wait-Job $build -Timeout 30 | Out-Null
    $os = Get-CimInstance Win32_OperatingSystem
    $java = @(Get-Process java -ErrorAction SilentlyContinue)
    $runner = @(Get-Process Runner.Worker -ErrorAction SilentlyContinue)
    $log = Get-Item windows-verify.log -ErrorAction SilentlyContinue
    $reports = @(Get-ChildItem target/surefire-reports/TEST-*.xml -ErrorAction SilentlyContinue)
    [ordered]@{
      time = [datetime]::UtcNow.ToString("o")
      freeMemoryMiB = [math]::Round($os.FreePhysicalMemory / 1024)
      javaWorkingSetMiB = [math]::Round(($java | Measure-Object WorkingSet64 -Sum).Sum / 1MB)
      javaHandles = ($java | Measure-Object HandleCount -Sum).Sum
      runnerWorkingSetMiB = [math]::Round(($runner | Measure-Object WorkingSet64 -Sum).Sum / 1MB)
      runnerCpuSeconds = ($runner | Measure-Object CPU -Sum).Sum
      freeDiskGiB = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
      completedClasses = $reports.Count
      logBytes = $log.Length
      logUpdated = $log.LastWriteTimeUtc.ToString("o")
    } | ConvertTo-Json -Compress | Write-Output
    if ($log -and $log.LastWriteTimeUtc -lt [datetime]::UtcNow.AddMinutes(-3) -and -not $dumpCaptured) {
      $dumpCaptured = $true
      foreach ($process in $java) {
        $dump = Start-Process "$env:JAVA_HOME/bin/jcmd.exe" -ArgumentList "$($process.Id)", 'Thread.print' -PassThru -NoNewWindow -RedirectStandardOutput "windows-threads-$($process.Id).log" -RedirectStandardError "windows-threads-$($process.Id).err"
        if (-not $dump.WaitForExit(15000)) { $dump.Kill() }
      }
    }
  }
  $result = Receive-Job $build -ErrorAction Stop
  if ($build.State -ne 'Completed' -or $null -eq $result.ExitCode) { throw 'Build job did not return an exit code' }
  Get-Content windows-verify.log -Tail 45
  exit $result.ExitCode
} finally {
  Stop-Job $build -ErrorAction SilentlyContinue
  Remove-Job $build
}
