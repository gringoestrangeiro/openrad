# Synthetic tests for the exact worker embedded in the Windows binaries.
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../../src/platform/windows_radmin.ps1'
$tokens = $null
$errors = $null
[Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count -ne 0) { throw ('Invalid recovery script: ' + ($errors -join '; ')) }
. $source

function Assert-Equal($Actual, $Expected, [string]$Reason) {
    if (($Actual -join '|') -ne ($Expected -join '|')) { throw ($Reason + ': ' + ($Actual -join '|')) }
}
function Assert-Rejected([scriptblock]$Action, [string]$Reason) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw $Reason }
}
function New-Adapter([uint32]$Index, [string]$Description = 'Famatech Radmin VPN Ethernet Adapter') {
    return [PSCustomObject]@{ InterfaceIndex = $Index; InterfaceGuid = ([Guid]::NewGuid().ToString('B')); InterfaceDescription = $Description }
}
function Reset-Case {
    $script:adapters = @((New-Adapter 19), (New-Adapter 20 'TAP-Windows Adapter V9'))
    $script:services = @(
        [PSCustomObject]@{ Name = 'RadminVpnService'; PathName = '"C:\Program Files\Radmin VPN\RvControlSvc.exe" /service'; Status = 'Running' },
        [PSCustomObject]@{ Name = 'OtherService'; PathName = 'C:\Other\server.exe'; Status = 'Running' }
    )
    $script:processes = @([PSCustomObject]@{ Id = 123; Name = 'RvControlSvc' })
    $script:actions = @()
    $script:stopResult = 0
    $script:killDenied = $false
    $script:disappearAtKill = $false
    $script:changedAdapter = $false
    $script:polls = 0
    $script:taskResult = 0
    $script:startDenied = $false
    $script:registrationDenied = $false
}
function Get-NetAdapter {
    param([switch]$IncludeHidden)
    return $script:adapters
}
function Get-CimInstance {
    param([string]$ClassName)
    Assert-Equal $ClassName 'Win32_Service' 'Queried an unexpected CIM class'
    return $script:services
}
function Invoke-CimMethod {
    param($InputObject, [string]$MethodName)
    Assert-Equal $MethodName 'StopService' 'Changed service configuration'
    $script:actions += ('stop:' + $InputObject.Name)
    if ($script:stopResult -eq 0) { $InputObject.Status = 'Stopped' }
    if ($script:changedAdapter) { $script:adapters = @((New-Adapter 19 'Ethernet')) }
    return [PSCustomObject]@{ ReturnValue = $script:stopResult }
}
function Get-Service {
    param([string]$Name)
    return $script:services | Where-Object { $_.Name -eq $Name }
}
function Get-Process {
    param([string]$Name, [int]$Id, $ErrorAction)
    if ($Name -and $Name -ne 'RvControlSvc') { throw 'Enumerated unrelated processes.' }
    if ($Id) { return $script:processes | Where-Object { $_.Id -eq $Id } }
    return $script:processes
}
function Stop-Process {
    param($InputObject, [switch]$Force, $ErrorAction)
    if (-not $Force) { throw 'Process termination did not use Force.' }
    $script:actions += ('kill:' + $InputObject.Id)
    if ($script:disappearAtKill) { $script:processes = @(); throw 'Process exited.' }
    if ($script:killDenied) { throw 'Access denied.' }
    $script:processes = @()
}
function Disable-NetAdapter {
    param([Parameter(ValueFromPipeline = $true)]$InputObject, [switch]$Confirm)
    process { $script:actions += ('disable:' + $InputObject.InterfaceIndex) }
}
function Start-Sleep {
    param([int]$Milliseconds)
    if ($script:polls -gt 10) { throw 'Scheduler polling did not finish.' }
}

Reset-Case
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19)
Assert-Equal $script:actions @('stop:RadminVpnService', 'kill:123', 'disable:19') 'Service/process/adapter order or scope is wrong'

Reset-Case
$script:services[0].PathName = 'C:\Program Files\Radmin VPN\rVcOnTrOlSvC.ExE'
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19)
Assert-Equal $script:actions @('stop:RadminVpnService', 'kill:123', 'disable:19') 'Unquoted executable path was not recognized'

Reset-Case
$script:services = @()
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19)
Assert-Equal $script:actions @('kill:123', 'disable:19') 'Orphaned process was not terminated'

Reset-Case
$script:services[0].Status = 'Stopped'
$script:stopResult = 6
$script:processes = @()
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19)
Assert-Equal $script:actions @('stop:RadminVpnService', 'disable:19') 'Already stopped service was rejected'

Reset-Case
$script:adapters += New-Adapter 21
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19, 21)
Assert-Equal $script:actions @('stop:RadminVpnService', 'kill:123', 'disable:19', 'disable:21') 'Multiple official adapters were not recovered together'

Reset-Case
Assert-Rejected { Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(20) } 'Unrelated adapter was accepted'
Assert-Equal $script:actions @() 'Mutated a service before validating the target'

Reset-Case
Assert-Rejected { Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19, 20) } 'Mixed adapter list was accepted'
Assert-Equal $script:actions @() 'Mutated a service before validating all targets'

Reset-Case
$script:adapters += New-Adapter 19
Assert-Rejected { Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19) } 'Duplicate interface index was accepted'
Assert-Equal $script:actions @() 'Ambiguous adapter caused mutations'

Reset-Case
$script:stopResult = 2
Assert-Rejected { Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19) } 'Denied service stop was ignored'
Assert-Equal $script:actions @('stop:RadminVpnService') 'Killed a service after its stop request was denied'

Reset-Case
$script:killDenied = $true
Assert-Rejected { Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19) } 'Denied process termination was ignored'
Assert-Equal $script:actions @('stop:RadminVpnService', 'kill:123') 'Disabled the adapter while its process was still running'

Reset-Case
$script:disappearAtKill = $true
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19)
Assert-Equal $script:actions @('stop:RadminVpnService', 'kill:123', 'disable:19') 'Process-exit race was treated as a failure'

Reset-Case
$script:changedAdapter = $true
Assert-Rejected { Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19) } 'Adapter replacement during recovery was ignored'
Assert-Equal $script:actions @('stop:RadminVpnService', 'kill:123') 'Disabled a replacement adapter'

Reset-Case
$script:services[0].PathName = '"C:\Other\service.exe" --helper C:\Radmin\RvControlSvc.exe'
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19)
Assert-Equal $script:actions @('kill:123', 'disable:19') 'Stopped a service mentioning Radmin only in its arguments'

Reset-Case
$script:services[0].PathName = 'C:\Other\service.exe --helper C:\Radmin\RvControlSvc.exe'
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19)
Assert-Equal $script:actions @('kill:123', 'disable:19') 'Stopped an unquoted service mentioning Radmin only in its arguments'

# Scheduler mocks: exercise registration, a Ready state before first execution,
# queued/running states, the real exit result, and finally cleanup on failures.
function Join-Path {
    param([string]$Path, [string]$ChildPath)
    Assert-Equal $ChildPath 'WindowsPowerShell\v1.0\powershell.exe' 'Task uses an unexpected executable'
    return 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
}
function New-ScheduledTaskAction {
    param([string]$Execute, [string]$Argument)
    Assert-Equal $Execute 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' 'Task does a PATH lookup'
    $encoded = ($Argument -split ' -EncodedCommand ')[1]
    $command = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($encoded))
    if (-not $command.Contains('Stop-Process') -or -not $command.Contains('-InterfaceIndex @(19)')) { throw 'Encoded task does not contain the fixed worker/targets.' }
    $tokens = $null; $errors = $null
    [Management.Automation.Language.Parser]::ParseInput($command, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors.Count -ne 0) { throw 'Encoded worker has syntax errors.' }
    return [PSCustomObject]@{ Execute = $Execute; Argument = $Argument }
}
function New-ScheduledTaskPrincipal {
    param([string]$UserId, [string]$LogonType, [string]$RunLevel)
    Assert-Equal @($UserId, $LogonType, $RunLevel) @('S-1-5-18', 'ServiceAccount', 'Highest') 'Task does not run as SYSTEM'
    return [PSCustomObject]@{ UserId = $UserId }
}
function New-ScheduledTaskSettingsSet {
    param([TimeSpan]$ExecutionTimeLimit, [switch]$AllowStartIfOnBatteries, [switch]$DontStopIfGoingOnBatteries)
    if ($ExecutionTimeLimit.TotalSeconds -ne 30 -or -not $AllowStartIfOnBatteries -or -not $DontStopIfGoingOnBatteries) { throw 'Task has no execution limit or fails on battery power.' }
    return [PSCustomObject]@{ ExecutionTimeLimit = $ExecutionTimeLimit }
}
function Register-ScheduledTask {
    param([string]$TaskName, $Action, $Principal, $Settings)
    if ($TaskName -notmatch '^OpenRad-RadminRecovery-[a-f0-9]{32}$') { throw 'Recovery task name is not unique.' }
    if ($script:registrationDenied) { throw 'Registration denied.' }
    $script:actions += 'register'
}
function Start-ScheduledTask {
    param([string]$TaskName)
    $script:actions += 'start'
    if ($script:startDenied) { throw 'Start denied.' }
}
function Get-ScheduledTask {
    param([string]$TaskName)
    $script:polls++
    $state = if ($script:polls -eq 2) { 'Queued' } elseif ($script:polls -eq 3) { 'Running' } else { 'Ready' }
    return [PSCustomObject]@{ State = $state }
}
function Get-ScheduledTaskInfo {
    param([string]$TaskName)
    $time = if ($script:polls -eq 1) { [DateTime]'1999-01-01' } else { [DateTime]::Now }
    $result = if ($script:polls -eq 2) { 267009 } elseif ($script:polls -eq 3) { 267011 } else { $script:taskResult }
    return [PSCustomObject]@{ LastRunTime = $time; LastTaskResult = $result }
}
function Stop-ScheduledTask {
    param([string]$TaskName, $ErrorAction)
    $script:actions += 'stop-task'
}
function Unregister-ScheduledTask {
    param([string]$TaskName, [switch]$Confirm)
    $script:actions += 'unregister'
}

Reset-Case
Invoke-OpenRadRadminRecovery -InterfaceIndex @(19)
Assert-Equal $script:actions @('register', 'start', 'stop-task', 'unregister') 'Successful task was not cleaned up'
Assert-Equal $script:polls 4 'Task success was accepted before its worker finished'

Reset-Case
$script:taskResult = 1
Assert-Rejected { Invoke-OpenRadRadminRecovery -InterfaceIndex @(19) } 'Worker failure was ignored'
Assert-Equal $script:actions @('register', 'start', 'stop-task', 'unregister') 'Failed task was not cleaned up'

Reset-Case
$script:startDenied = $true
Assert-Rejected { Invoke-OpenRadRadminRecovery -InterfaceIndex @(19) } 'Task start failure was ignored'
Assert-Equal $script:actions @('register', 'start', 'stop-task', 'unregister') 'Task start failure leaked a task'

Reset-Case
$script:registrationDenied = $true
Assert-Rejected { Invoke-OpenRadRadminRecovery -InterfaceIndex @(19) } 'Registration failure was ignored'
Assert-Equal $script:actions @() 'Cleaned up a task that was not registered'

Write-Host 'PASS: 18 synthetic Radmin worker/scheduler cases and embedded/encoded script syntax. No Windows services, processes, adapters, or scheduled tasks were changed.'
