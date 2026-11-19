$ErrorActionPreference = 'Stop'
$source = [IO.File]::ReadAllText((Join-Path (Split-Path $PSScriptRoot -Parent) 'desktop/src/windows_startup.rs'))

function Read-StartupSource([string]$name) {
    $pattern = '(?s)const ' + $name + ': &str = r#"(.*?)"#;'
    $match = [regex]::Match($source, $pattern)
    if (-not $match.Success) { throw "Missing $name" }
    return $match.Groups[1].Value
}

function Read-StartupScript([string]$name) {
    $script = Read-StartupSource $name
    if ($name -eq 'SCRIPT') {
        $script = (Read-StartupSource 'VERIFY_INSTALL') + "`n" + $script
    }
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseInput($script, [ref]$tokens, [ref]$errors) | Out-Null
    if (@($errors).Count -gt 0) { throw "Invalid PowerShell script $name" }
    foreach ($command in @('Get-ScheduledTask', 'Register-ScheduledTask', 'Unregister-ScheduledTask', 'Test-Path', 'Get-Item', 'Get-Acl', 'Get-ChildItem')) {
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
$installDirectory = Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'OpenRad'
$desktop = Join-Path $installDirectory 'openrad-desktop.exe'
$cli = Join-Path $installDirectory 'openrad.exe'
$dll = Join-Path $installDirectory 'runtime.dll'
$volume = [IO.Path]::GetPathRoot($desktop)
$global:testInstallItems = @{}
$global:testInstallAcls = @{}

function New-TestAcl([bool]$writable = $false, [bool]$userOwned = $false) {
    $acl = [Security.AccessControl.FileSecurity]::new()
    $owner = if ($userOwned) { 'S-1-5-21-1-2-3-1001' } else { 'S-1-5-32-544' }
    $acl.SetOwner([Security.Principal.SecurityIdentifier]::new($owner))
    $rights = if ($writable) { 'Modify' } else { 'ReadAndExecute' }
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        [Security.Principal.SecurityIdentifier]::new('S-1-1-0'), $rights, 'Allow'))
    return $acl
}

function Reset-TestInstall {
    $global:testInstallItems.Clear()
    $global:testInstallAcls.Clear()
    $paths = @($desktop, $cli, $dll)
    $directory = $installDirectory
    while ($directory) {
        $paths += $directory
        $directory = [IO.Path]::GetDirectoryName($directory)
    }
    foreach ($path in $paths) {
        $attributes = if ($path -in @($desktop, $cli, $dll)) {
            [IO.FileAttributes]::Normal
        } else { [IO.FileAttributes]::Directory }
        $global:testInstallItems[$path] = [pscustomobject]@{ FullName = $path; Attributes = $attributes }
        $global:testInstallAcls[$path] = New-TestAcl
    }
}

function Mock-Test-Path {
    param([string]$LiteralPath, [string]$PathType)
    return $global:testInstallItems.ContainsKey($LiteralPath) -and
        ($global:testInstallItems[$LiteralPath].Attributes -band [IO.FileAttributes]::Directory) -eq 0
}
function Mock-Get-Item {
    param([string]$LiteralPath, [switch]$Force, [string]$ErrorAction)
    if (-not $global:testInstallItems.ContainsKey($LiteralPath)) { throw "Missing fixture: $LiteralPath" }
    return $global:testInstallItems[$LiteralPath]
}
function Mock-Get-Acl {
    param([string]$LiteralPath, [string]$ErrorAction)
    if (-not $global:testInstallAcls.ContainsKey($LiteralPath)) { throw "Unavailable ACL: $LiteralPath" }
    return $global:testInstallAcls[$LiteralPath]
}
function Mock-Get-ChildItem {
    param([string]$LiteralPath, [switch]$File, [string]$Filter, [switch]$Force)
    if ($LiteralPath -ne $installDirectory -or $Filter -ne '*.dll') { throw 'Unexpected DLL enumeration' }
    return $global:testInstallItems[$dll]
}

Reset-TestInstall
$register = Read-StartupScript 'SCRIPT'
$removeAll = Read-StartupScript 'REMOVE_SCRIPT'
$env:OPENRAD_STARTUP_TASK = 'OpenRad-VPN-0000000000000001'
$env:OPENRAD_STARTUP_EXEC = $desktop
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
function Assert-RejectedInstall([string]$message) {
    $before = $global:testRegisters
    $failure = $null
    try { & $register } catch { $failure = $_ }
    if ($null -eq $failure -or $failure.Exception.Message -notmatch $message) {
        throw "Expected installation rejection '$message', got: $failure"
    }
    if ($global:testRegisters -ne $before) { throw 'Unsafe installation reached task registration' }
}
foreach ($path in @($desktop, $cli, $dll, $installDirectory, $volume)) {
    Reset-TestInstall
    $global:testInstallAcls[$path] = New-TestAcl -writable $true
    Assert-RejectedInstall 'not writable by ordinary users'
}
Reset-TestInstall
$global:testInstallAcls[$desktop] = New-TestAcl -userOwned $true
Assert-RejectedInstall 'administrator-owned installation'
Reset-TestInstall
$global:testInstallItems[$installDirectory].Attributes = [IO.FileAttributes]::Directory -bor [IO.FileAttributes]::ReparsePoint
Assert-RejectedInstall 'redirected installation path'
Reset-TestInstall
$global:testInstallAcls.Remove($cli)
Assert-RejectedInstall 'Unavailable ACL'
Reset-TestInstall
$outside = Join-Path $volume 'Users\Example Person\Downloads\openrad-desktop.exe'
$global:testInstallItems[$outside] = [pscustomobject]@{ FullName = $outside; Attributes = [IO.FileAttributes]::Normal }
$env:OPENRAD_STARTUP_EXEC = $outside
Assert-RejectedInstall 'under Program Files'

Write-Output 'PASS: Windows startup registration, ownership, idempotence, profile arguments, uninstall scoping, ACLs and redirected path rejection. No OS tasks changed.'
