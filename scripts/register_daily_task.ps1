# Register a Windows scheduled task: run scripts/daily_stack.sh every day at 09:07.
# Deterministic on purpose: no LLM / session model involved.
#
# Why (2026-11-16): the in-app scheduled task started failing on 2026-11-07 because the
# model id it was bound to had been retired; the task died silently at model selection and
# the gate-7 clock was stopped for 9 days before anyone noticed. With a system task plus a
# plain script, the outcome is visible only through the exit code and logs/daily-stack.log.
#
# Usage (normal user rights, no admin needed):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/register_daily_task.ps1
# Verify:
#   Get-ScheduledTask -TaskName "OpsPilot Daily Stack" | Get-ScheduledTaskInfo
# Run now:
#   Start-ScheduledTask -TaskName "OpsPilot Daily Stack"
# Uninstall:
#   Unregister-ScheduledTask -TaskName "OpsPilot Daily Stack" -Confirm:$false
#
# NOTE: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less files as ANSI).

$ErrorActionPreference = "Stop"

$Repo   = Split-Path -Parent $PSScriptRoot
$Script = Join-Path $Repo "scripts\daily_stack.sh"
if (-not (Test-Path $Script)) { throw "not found: $Script" }

$Bash = "E:\Git\bin\bash.exe"
if (-not (Test-Path $Bash)) { throw "Git Bash not found: $Bash (edit this script if your path differs)" }

$ScriptUnix = $Script -replace '\\', '/'
$Action  = New-ScheduledTaskAction -Execute $Bash `
           -Argument ("-lc `"bash '" + $ScriptUnix + "'`"") `
           -WorkingDirectory $Repo
$Trigger = New-ScheduledTaskTrigger -Daily -At 09:07
$Settings = New-ScheduledTaskSettingsSet -StartWhenAvailable `
           -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -MultipleInstances IgnoreNew

Register-ScheduledTask -TaskName "OpsPilot Daily Stack" -Action $Action -Trigger $Trigger `
    -Settings $Settings -Description "Gate 6/7 observation: keep live stack and weekly report (scripts/daily_stack.sh)" -Force | Out-Null

$info = Get-ScheduledTask -TaskName "OpsPilot Daily Stack" | Get-ScheduledTaskInfo
Write-Host ("registered: OpsPilot Daily Stack; next run {0}" -f $info.NextRunTime)
