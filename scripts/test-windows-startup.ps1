$ErrorActionPreference = 'Stop'
$source = [IO.File]::ReadAllText((Join-Path (Split-Path $PSScriptRoot -Parent) 'desktop/src/windows_startup.rs'))

function Read-StartupScript([string]$name) {
    $pattern = '(?s)const ' + $name + ': &str = r#"(.*?)"#;'
    $match = [regex]::Match($source, $pattern)
    if (-not $match.Success) { throw "Missing $name" }
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseInput($match.Groups[1].Value, [ref]$tokens, [ref]$errors) | Out-Null
    if (@($errors).Count -gt 0) { throw "Invalid PowerShell script $name" }
    $script = $match.Groups[1].Value
    foreach ($command in @('Get-ScheduledTask', 'Register-ScheduledTask', 'Unregister-ScheduledTask', 'Test-Path')) {
        $script = $script.Replace($command, ('Mock-' + $command))
    }
    return [scriptblock]::Create($script)
}

$global:testScheduledTasks = @{}
$global:testRegisters = 0
$global:testDeletes = 0
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()

function Mock-Get-ScheduledTask {
    param([string]$TaskName, [string]$TaskPath, [string]$ErrorAction)
    if ($TaskName.Contains('*')) {
        return @($global:testScheduledTasks.Values | Where-Object { $_.TaskName -like $TaskName })
    }
    return $global:testScheduledTasks[$TaskName]
}
function Mock-Register-ScheduledTask {
    param([string]$TaskName, [string]$TaskPath, $Action, $Trigger, $Principal, $Settings, [switch]$Force, [string]$ErrorAction)
    $global:testRegisters++
    $task = [pscustomobject]@{
        TaskName = $TaskName
        Principal = $Principal
        Actions = @($Action)
    }
    $global:testScheduledTasks[$TaskName] = $task
    return $task
}
function Mock-Unregister-ScheduledTask {
    param([string]$TaskName, [string]$TaskPath, [switch]$Confirm, [string]$ErrorAction)
    if (-not $global:testScheduledTasks.ContainsKey($TaskName)) { throw 'Unexpected unregister' }
    $global:testDeletes++
    $global:testScheduledTasks.Remove($TaskName)
}
function Mock-Test-Path {
    param([string]$LiteralPath, [switch]$PathType)
    return $LiteralPath -in @($env:OPENRAD_STARTUP_EXEC, 'C:\Program Files\OpenRad\openrad.exe')
}

$register = Read-StartupScript 'SCRIPT'
$removeAll = Read-StartupScript 'REMOVE_SCRIPT'
$env:OPENRAD_STARTUP_TASK = 'OpenRad-VPN-0000000000000001'
$env:OPENRAD_STARTUP_EXEC = 'C:\Program Files\OpenRad\openrad-desktop.exe'
$env:OPENRAD_STARTUP_DIR = 'C:\Users\Example Person\AppData\Local\OpenRad'
$env:OPENRAD_STARTUP_ENABLE = '1'

& $register
if ($global:testScheduledTasks.Count -ne 1 -or $global:testRegisters -ne 1) { throw 'Enable did not register one task' }
$task = $global:testScheduledTasks[$env:OPENRAD_STARTUP_TASK]
if ($task.Actions[0].Arguments -notmatch '--background --data-dir') { throw 'Scheduled action does not start the background VPN' }
if ($task.Principal.LogonType -ne 'Interactive' -or $task.Principal.RunLevel -ne 'Highest') {
    throw 'Task cannot use an interactive highest-privilege principal'
}
& $register
if ($global:testScheduledTasks.Count -ne 1) { throw 'Repeated enable duplicated the task' }

$global:testScheduledTasks[$env:OPENRAD_STARTUP_TASK].Principal.UserId = 'S-1-5-18'
$blocked = $false
try { & $register } catch { $blocked = $true }
if (-not $blocked) { throw 'Another user was allowed to replace the task' }
$global:testScheduledTasks[$env:OPENRAD_STARTUP_TASK].Principal.UserId = $currentUser.User.Value

$env:OPENRAD_STARTUP_ENABLE = '0'
& $register
if ($global:testScheduledTasks.Count -ne 0 -or $global:testDeletes -ne 1) { throw 'Disable did not unregister the task' }
& $register
if ($global:testDeletes -ne 1) { throw 'Repeated disable was not idempotent' }

$global:testScheduledTasks['OpenRad-VPN-0000000000000001'] = [pscustomobject]@{
    TaskName = 'OpenRad-VPN-0000000000000001'
    Actions = @([pscustomobject]@{ Execute = $env:OPENRAD_STARTUP_EXEC })
    Principal = [pscustomobject]@{ UserId = $currentUser.User.Value }
}
$global:testScheduledTasks['OpenRad-VPN-0000000000000002'] = [pscustomobject]@{
    TaskName = 'OpenRad-VPN-0000000000000002'
    Actions = @([pscustomobject]@{ Execute = 'C:\Program Files\OtherApp\other.exe' })
    Principal = [pscustomobject]@{ UserId = $currentUser.User.Value }
}
& $removeAll
if ($global:testScheduledTasks.Count -ne 1 -or -not $global:testScheduledTasks.ContainsKey('OpenRad-VPN-0000000000000002')) {
    throw 'Uninstall removed a task owned by another executable'
}

$env:OPENRAD_STARTUP_ENABLE = '1'
$env:OPENRAD_STARTUP_EXEC = 'C:\Users\Example Person\Downloads\openrad-desktop.exe'
$blocked = $false
try { & $register } catch { $blocked = $true }
if (-not $blocked) { throw 'Allowed privileged autostart of a user-writable executable' }

Write-Output 'PASS: Windows startup registration, ownership, idempotence, profile arguments, uninstall scoping and unsafe path rejection. No OS tasks changed.'
