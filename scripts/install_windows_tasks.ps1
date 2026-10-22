<#
.SYNOPSIS
    OpsShield Windows 计划任务安装（G0，2026-10-22，幂等）。

.DESCRIPTION
    注册/更新四个任务（同名先反注册再注册，注册后立刻用 Get-ScheduledTask 回读验证）：
      OpsPilot-Gateway   开机启动（非管理员退化为登录启动）前台跑 scripts/start_gateway.sh --foreground
      OpsPilot-Backup    每日 02:00 跑 scripts/backup.sh（StartWhenAvailable=错过则开机后补跑）
      OpsPilot-Watchdog  每 5 分钟跑 scripts/watchdog_alerts.py --once（--once 比常驻更抗"任务被结束"）
      OpsPilot-Liveness  每 5 分钟跑 scripts/liveness.py --once（外部存活探测，退出码表态）

    为什么不用 schtasks.exe：Register-ScheduledTask 有对象模型，触发器/设置能回读，
    才能把"装上了"变成"可验证"（Get-ScheduledTask 查得到、State 可读）。
    为什么用 Git Bash 而不是 WSL bash：WSL 的 /bin/bash 里仓库路径与工具链是另一套
    （unzip/sha256sum 的可用性都不同），脚本会静默走到不同的分支。

.PARAMETER RepoRoot
    仓库根目录；默认本脚本上一级（scripts/ 的父目录）。

.PARAMETER BashPath
    Git Bash 的 bash.exe；默认从 git.exe 推导，再退到 Program Files。
    显式指定示例：-BashPath 'C:\Program Files\Git\bin\bash.exe'

.PARAMETER PythonPath
    Python 解释器；默认 offline/.venv/Scripts/python.exe，否则 PATH 里的 python.exe。
    watchdog/liveness 只用标准库，系统 Python 即可。

.PARAMETER BackupAt
    每日备份时刻（HH:mm），默认 02:00（与 OPS §8 cron、ops/systemd/opspilot-backup.timer 同口径）。

.PARAMETER Uninstall
    反注册这四个任务（幂等：不存在的直接跳过）。

.PARAMETER DryRun
    只解析路径与动作并打印，不写任务计划（无管理员权限/CI 里也能校验脚本逻辑）。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install_windows_tasks.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install_windows_tasks.ps1 -DryRun

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install_windows_tasks.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = "",
    [string]$BashPath = "",
    [string]$PythonPath = "",
    [string]$BackupAt = "02:00",
    [switch]$Uninstall,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$TaskNames = @("OpsPilot-Gateway", "OpsPilot-Backup", "OpsPilot-Watchdog", "OpsPilot-Liveness")

function Fail([string]$msg) {
    Write-Error $msg
    exit 1
}

# ---------------- 路径解析 ----------------
if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent $PSScriptRoot }
if (-not (Test-Path -LiteralPath $RepoRoot)) { Fail "仓库根不存在: $RepoRoot" }
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot "scripts\backup.sh"))) {
    Fail "RepoRoot 不像 OpsPilot 仓库（缺 scripts/backup.sh）: $RepoRoot"
}

function Resolve-Bash([string]$explicit) {
    if ($explicit) {
        if (-not (Test-Path -LiteralPath $explicit)) { Fail "BashPath 不存在: $explicit" }
        return (Resolve-Path -LiteralPath $explicit).Path
    }
    $git = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($git) {
        # E:\Git\cmd\git.exe -> E:\Git\bin\bash.exe
        $candidate = Join-Path (Split-Path -Parent (Split-Path -Parent $git.Source)) "bin\bash.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    foreach ($p in @("$env:ProgramFiles\Git\bin\bash.exe", "${env:ProgramFiles(x86)}\Git\bin\bash.exe")) {
        if ($p -and (Test-Path -LiteralPath $p)) { return $p }
    }
    $cmd = Get-Command bash.exe -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source -notlike "*System32*") { return $cmd.Source }
    Fail "找不到 Git Bash（WSL 的 bash 不能直接跑仓库脚本）。请用 -BashPath 指定，如 'C:\Program Files\Git\bin\bash.exe'"
}

function Resolve-Python([string]$explicit) {
    if ($explicit) {
        if (-not (Test-Path -LiteralPath $explicit)) { Fail "PythonPath 不存在: $explicit" }
        return (Resolve-Path -LiteralPath $explicit).Path
    }
    $venv = Join-Path $RepoRoot "offline\.venv\Scripts\python.exe"
    if (Test-Path -LiteralPath $venv) { return $venv }
    $py = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($py) { return $py.Source }
    Fail "找不到 Python（Watchdog/Liveness 任务需要）。请用 -PythonPath 指定。"
}

$BashPath = Resolve-Bash $BashPath
$PythonPath = Resolve-Python $PythonPath
try { [void][datetime]::ParseExact($BackupAt, "HH:mm", $null) }
catch { Fail "BackupAt 必须是 HH:mm（如 02:00），收到: $BackupAt" }

# 把 Windows 路径转成 Git Bash 的 /c/... 形式：任务动作里用 POSIX 路径，
# 避免在 bash 命令行里处理盘符冒号与反斜杠的二次转义。
function To-PosixPath([string]$winPath) {
    $forward = $winPath.Replace("\", "/")
    $posix = (& $BashPath -lc "cygpath -u '$forward'").Trim()
    if (-not $posix) { Fail "cygpath 转换失败: $winPath" }
    return $posix
}

$RepoPosix = To-PosixPath $RepoRoot
$PythonPosix = To-PosixPath $PythonPath
Write-Host "仓库: $RepoRoot"
Write-Host "  POSIX: $RepoPosix"
Write-Host "Git Bash: $BashPath"
Write-Host "Python: $PythonPath"

# ---------------- 卸载 ----------------
if ($Uninstall) {
    foreach ($name in $TaskNames) {
        $t = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
        if ($t) {
            if ($DryRun) {
                Write-Host "  [DryRun] 将反注册 $name"
            } else {
                Unregister-ScheduledTask -TaskName $name -Confirm:$false
                Write-Host "  已反注册 $name"
            }
        } else {
            Write-Host "  跳过（不存在）$name"
        }
    }
    if (-not $DryRun) {
        $left = @(Get-ScheduledTask -TaskName "OpsPilot-*" -ErrorAction SilentlyContinue)
        Write-Host ("反注册后剩余 OpsPilot-* 任务: {0}" -f $left.Count)
    }
    exit 0
}

# ---------------- 权限与主体 ----------------
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
$runLevel = if ($isAdmin) { "Highest" } else { "Limited" }
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel $runLevel
if (-not $isAdmin) {
    Write-Host "提示：当前不是管理员。任务以当前用户、普通权限注册（仅登录时运行）；"
    Write-Host "      '开机启动'需要管理员权限，本脚本会退化为'登录启动'。以管理员重跑可恢复真开机启动。"
}

# ---------------- 任务动作 ----------------
function New-BashAction([string]$commandLine, [string]$workDir) {
    # -lc 让 bash 读登录脚本（PATH 里有 git 工具链）；命令里的路径用 POSIX 形式
    return New-ScheduledTaskAction -Execute $BashPath -Argument ("-lc ""cd '" + $RepoPosix + "' && " + $commandLine + """") -WorkingDirectory $workDir
}

$gatewayAction = New-BashAction "bash scripts/start_gateway.sh --foreground" $RepoRoot
$backupAction = New-BashAction "bash scripts/backup.sh >> logs/backup.task.log 2>&1" $RepoRoot
$watchdogAction = New-BashAction ("'" + $PythonPosix + "' scripts/watchdog_alerts.py --once >> logs/watchdog.task.log 2>&1") $RepoRoot
$livenessAction = New-BashAction ("'" + $PythonPosix + "' scripts/liveness.py --once >> logs/liveness.task.log 2>&1") $RepoRoot

# ---------------- 触发器与设置 ----------------
$gatewayTrigger = if ($isAdmin) { New-ScheduledTaskTrigger -AtStartup } else { New-ScheduledTaskTrigger -AtLogOn }
$backupTrigger = New-ScheduledTaskTrigger -Daily -At $BackupAt
# 每 5 分钟一次：PS 5.1 里 RepetitionDuration 用有限值（MaxValue 在部分版本注册失败）
$every5 = New-TimeSpan -Minutes 5
$repeatFor = New-TimeSpan -Days 3650
$startAt = (Get-Date).AddMinutes(1)
# 非管理员时没有"开机"语义，探针与网关一样退化为登录启动
$watchdogTriggers = @(
    $gatewayTrigger,
    (New-ScheduledTaskTrigger -Once -At $startAt -RepetitionInterval $every5 -RepetitionDuration $repeatFor)
)
$livenessTriggers = $watchdogTriggers

$gatewaySettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
$backupSettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 1)
$probeSettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 10)

# ---------------- 注册（幂等 + 回读验证）----------------
function Register-OpsTask {
    param(
        [string]$Name,
        $Action,
        $Trigger,
        $Settings,
        [string]$Description
    )
    $existing = Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
    if ($DryRun) {
        Write-Host ("  [DryRun] {0}: {1} {2}" -f $Name, $Action.Execute, $Action.Arguments)
        if ($existing) { Write-Host ("  [DryRun] 已存在同名任务（真跑时会先反注册再注册）: {0}" -f $Name) }
        return
    }
    if ($existing) {
        Unregister-ScheduledTask -TaskName $Name -Confirm:$false
        Write-Host ("  已存在同名任务，先反注册（幂等）: {0}" -f $Name)
    }
    Register-ScheduledTask -TaskName $Name -Action $Action -Trigger $Trigger -Settings $Settings -Principal $principal -Description $Description -Force | Out-Null
    # 回读验证：注册 API 不抛异常不代表任务真的在（权限/策略拒绝会静默失败）
    $verify = Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
    if (-not $verify) { Fail ("注册后 Get-ScheduledTask 查不到任务: {0}" -f $Name) }
    Write-Host ("  OK {0}  State={1}" -f $Name, $verify.State)
}

Write-Host "注册计划任务（RunLevel=$runLevel, BackupAt=$BackupAt）:"
Register-OpsTask -Name "OpsPilot-Gateway" -Action $gatewayAction -Trigger $gatewayTrigger -Settings $gatewaySettings -Description "OpsShield 网关（前台，开机/登录启动；退出后自动重启）"
Register-OpsTask -Name "OpsPilot-Backup" -Action $backupAction -Trigger $backupTrigger -Settings $backupSettings -Description "OpsShield 每日备份（02:00；错过则开机补跑；产物校验通过才写 .last-success.json）"
Register-OpsTask -Name "OpsPilot-Watchdog" -Action $watchdogAction -Trigger $watchdogTriggers -Settings $probeSettings -Description "OpsShield Docker 告警看门狗（每 5 分钟扫一轮容器状态翻转）"
Register-OpsTask -Name "OpsPilot-Liveness" -Action $livenessAction -Trigger $livenessTriggers -Settings $probeSettings -Description "OpsShield 外部存活探测（每 5 分钟一次；退出码 0=健康/1=异常/2=地址非法）"

if (-not $DryRun) {
    Write-Host ""
    Write-Host "验证（Get-ScheduledTask 回读）:"
    Get-ScheduledTask -TaskName "OpsPilot-*" |
        Select-Object TaskName, State |
        Format-Table -AutoSize |
        Out-String -Width 120 |
        Write-Host
    Write-Host "日志: logs/backup.task.log / logs/watchdog.task.log / logs/liveness.task.log"
    Write-Host "卸载: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install_windows_tasks.ps1 -Uninstall"
}
