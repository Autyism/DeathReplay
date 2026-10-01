# Runs the in-game self test (-Ddr.selftest=true) and waits for it to finish.
#
# Starts `gradlew runClient` as a separate process, polls run/logs/latest.log until
# "[SelfTest] DONE", a crash, or the timeout (6 minutes), then makes sure the game's
# java process is gone. Exit code 0 = PASS, 1 = anything else.
#
# Usage (from anywhere):  powershell -ExecutionPolicy Bypass -File scripts\selftest.ps1
param(
	[int]$TimeoutSeconds = 360,
	# Run with Sodium in the dev client (I play with it; it replaces the chunk renderer).
	[switch]$Sodium
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$log = Join-Path $root 'run\logs\latest.log'
$gradleOut = Join-Path $root 'build\selftest-gradle.log'
$gradleErr = Join-Path $root 'build\selftest-gradle.err.log'
$crashDir = Join-Path $root 'run\crash-reports'

# Only ever touch the game process that belongs to THIS project. Other java processes on
# this machine (the Minecraft server, other projects' dev clients, Gradle daemons) are off limits.
function Get-GameProcess {
	Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
		Where-Object { $_.CommandLine -like "*-Dfabric.dli.config=$root\*" }
}

if (Get-GameProcess) {
	Write-Output '[selftest.ps1] a dev client of this project is already running; refusing to start another one'
	exit 1
}

New-Item -ItemType Directory -Force (Join-Path $root 'build') | Out-Null
if (Test-Path $log) { Remove-Item $log -Force }
$crashesBefore = @(Get-ChildItem $crashDir -Filter '*.txt' -ErrorAction SilentlyContinue).Count

$gradleArgs = @('runClient', '--console=plain', '"-Ddr.selftest=true"')
if ($Sodium) { $gradleArgs += '-Pwith_sodium=true' }
$gradle = Start-Process -FilePath (Join-Path $root 'gradlew.bat') `
	-ArgumentList $gradleArgs `
	-WorkingDirectory $root -WindowStyle Hidden -PassThru `
	-RedirectStandardOutput $gradleOut -RedirectStandardError $gradleErr

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$outcome = 'TIMEOUT'
while ((Get-Date) -lt $deadline) {
	Start-Sleep -Seconds 3
	$text = if (Test-Path $log) { Get-Content $log -Raw -ErrorAction SilentlyContinue } else { '' }
	if ($text -cmatch '\[SelfTest\] DONE') { $outcome = 'DONE'; break }
	$crashesNow = @(Get-ChildItem $crashDir -Filter '*.txt' -ErrorAction SilentlyContinue).Count
	if ($crashesNow -gt $crashesBefore) { $outcome = 'CRASH'; break }
	if ($gradle.HasExited) { $outcome = 'EXITED'; break }
}

# Give a finished game a moment to shut down by itself, then force-stop whatever is left.
$exitDeadline = (Get-Date).AddSeconds($(if ($outcome -eq 'DONE') { 40 } else { 5 }))
while ((Get-GameProcess) -and (Get-Date) -lt $exitDeadline) { Start-Sleep -Seconds 2 }
$killed = $false
Get-GameProcess | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; $killed = $true }
Start-Sleep -Seconds 2
$stillRunning = @(Get-GameProcess).Count

Write-Output "[selftest.ps1] outcome: $outcome"
if (Test-Path $log) {
	Select-String -Path $log -Pattern '\[SelfTest\]' -CaseSensitive | ForEach-Object { $_.Line }
} else {
	Write-Output '[selftest.ps1] no latest.log was written; tail of gradle output:'
	if (Test-Path $gradleOut) { Get-Content $gradleOut -Tail 30 }
}
if ($outcome -eq 'CRASH') {
	$report = Get-ChildItem $crashDir -Filter '*.txt' | Sort-Object LastWriteTime | Select-Object -Last 1
	Write-Output "[selftest.ps1] crash report: $($report.FullName)"
	Get-Content $report.FullName -TotalCount 40
}
Write-Output "[selftest.ps1] game process force-stopped: $killed; game processes still running: $stillRunning"

$pass = ($outcome -eq 'DONE') -and ((Get-Content $log -Raw) -cmatch '\[SelfTest\] RESULT PASS') -and ($stillRunning -eq 0)
Write-Output "[selftest.ps1] $(if ($pass) { 'PASS' } else { 'FAIL' })"
exit $(if ($pass) { 0 } else { 1 })
