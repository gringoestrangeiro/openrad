# Synthetic tests for the exact worker embedded in the Windows binaries.
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../../src/platform/windows_radmin.ps1'
$tokens = $null
$errors = $null
[Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count -ne 0) { throw ('Invalid recovery script: ' + ($errors -join '; ')) }
. $source
$originalResultReader = ${function:Read-OpenRadRadminResult}
if (-not [IO.File]::ReadAllText($source).Contains('$security.SetAccessRuleProtection($true, $false)')) { throw 'Diagnostic pipe does not protect its ACL.' }

function Assert-Equal($Actual, $Expected, [string]$Reason) {
    if (($Actual -join '|') -ne ($Expected -join '|')) { throw ($Reason + ': ' + ($Actual -join '|')) }
}
function Assert-Rejected([scriptblock]$Action, [string]$Reason) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw $Reason }
}
function Assert-ErrorContains([scriptblock]$Action, [string]$Expected) {
    try { & $Action } catch {
        if (-not $_.Exception.Message.Contains($Expected)) { throw ('Missing error detail: ' + $_.Exception.Message) }
        return
    }
    throw ('Expected failure: ' + $Expected)
}
function New-Adapter([uint32]$Index, [string]$Description = 'Famatech Radmin VPN Ethernet Adapter') {
    return [PSCustomObject]@{ InterfaceIndex = $Index; InterfaceGuid = ([Guid]::NewGuid().ToString('B')); InterfaceDescription = $Description; InterfaceAdminStatus = 1 }
}
function Reset-Case {
    $env:PSModulePath = 'synthetic-untrusted-modules'
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
    $script:startRace = $false
    $script:stopThrows = $false
    $script:disableDenied = $false
    $script:reenable = $false
    $script:verificationReads = 0
    $script:pipeDisposed = $false
    $script:taskError = 'synthetic worker failure: adapter access denied'
}
function Get-NetAdapter {
    param([switch]$IncludeHidden)
    Assert-Equal $env:PSModulePath ([IO.Path]::Combine([Environment]::SystemDirectory, 'WindowsPowerShell\v1.0\Modules')) 'Recovery resolved cmdlets through an untrusted module path'
    if ($script:actions -match '^disable:') {
        $script:verificationReads++
        if ($script:reenable -and $script:verificationReads -ge 5) { $script:adapters[0].InterfaceAdminStatus = 1 }
    }
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
    if ($script:stopThrows) { throw 'CIM service access denied' }
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
    process {
        $script:actions += ('disable:' + $InputObject.InterfaceIndex)
        if ($script:disableDenied) { throw 'synthetic adapter access denied' }
        $InputObject.InterfaceAdminStatus = 2
    }
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
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19)
Assert-Equal $script:actions @('stop:RadminVpnService', 'disable:19') 'Killed a service after its stop request was denied or skipped adapter recovery'
Assert-Equal $script:verificationReads 10 'Did not observe a disabled adapter for two seconds'

Reset-Case
$script:stopResult = 5
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19)
Assert-Equal $script:actions @('stop:RadminVpnService', 'disable:19') 'Unsupported service stop prevented adapter recovery'

Reset-Case
$script:stopThrows = $true
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19)
Assert-Equal $script:actions @('stop:RadminVpnService', 'disable:19') 'CIM stop failure prevented adapter recovery'

Reset-Case
$script:stopResult = 2
$script:disableDenied = $true
Assert-ErrorContains { Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19) } 'Disable-NetAdapter: synthetic adapter access denied; StopService RadminVpnService returned 2'
Assert-Equal $script:actions @('stop:RadminVpnService', 'disable:19') 'Killed a protected service on failed recovery'

Reset-Case
$script:stopResult = 2
$script:reenable = $true
Assert-ErrorContains { Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19) } 'did not remain administratively disabled; StopService RadminVpnService returned 2'
Assert-Equal $script:verificationReads 50 'Adapter-state verification did not have a bounded retry'

Reset-Case
$script:killDenied = $true
Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19)
Assert-Equal $script:actions @('stop:RadminVpnService', 'kill:123', 'disable:19') 'Denied process termination prevented adapter recovery'

Reset-Case
$script:killDenied = $true
$script:disableDenied = $true
Assert-ErrorContains { Invoke-OpenRadRadminRecoveryWorker -InterfaceIndex @(19) } 'Stop-Process RvControlSvc.exe: Access denied.'

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
function New-OpenRadRadminResultPipe {
    param([string]$Name)
    if ($Name -notmatch '^OpenRad-RadminRecovery-[a-f0-9]{32}$') { throw 'Diagnostic pipe name is not unique.' }
    $pipe = [PSCustomObject]@{}
    $pipe | Add-Member ScriptMethod BeginWaitForConnection { param($Callback, $State); return [PSCustomObject]@{ IsCompleted = $true } }
    $pipe | Add-Member ScriptMethod Dispose { $script:pipeDisposed = $true }
    return $pipe
}
function Read-OpenRadRadminResult {
    param($Pipe, $Connection)
    return $script:taskError
}
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
    $script:workerCommand = $command
    if (-not $command.Contains('Stop-Process') -or -not $command.Contains('-InterfaceIndex @(19)')) { throw 'Encoded task does not contain the fixed worker/targets.' }
    if (-not $command.Contains('[IO.Pipes.NamedPipeClientStream]::new') -or -not $command.Contains('$message = $_.Exception.Message')) { throw 'Worker discards its failure details.' }
    if ($command.Contains('WriteAllText') -or $Argument.Length -gt 30000) { throw 'Worker writes a diagnostic file as SYSTEM or exceeds command length limits.' }
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
    $state = if ($script:polls -eq 1 -and $script:startRace) { 'Running' } elseif ($script:polls -eq 2) { 'Queued' } elseif ($script:polls -eq 3) { 'Running' } else { 'Ready' }
    return [PSCustomObject]@{ State = $state }
}
function Get-ScheduledTaskInfo {
    param([string]$TaskName)
    $script:polls++
    $time = if ($script:polls -eq 1 -and -not $script:startRace) { [DateTime]'1999-01-01' } else { [DateTime]::Now }
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
Assert-Equal $script:pipeDisposed $true 'Successful recovery leaked its diagnostic pipe'

Reset-Case
$script:startRace = $true
Invoke-OpenRadRadminRecovery -InterfaceIndex @(19)
Assert-Equal $script:polls 4 'An old Ready state and a new LastRunTime falsely signaled completion'
Assert-Equal $script:actions @('register', 'start', 'stop-task', 'unregister') 'Start-race task was not cleaned up'

Reset-Case
$script:taskResult = 1
Assert-ErrorContains { Invoke-OpenRadRadminRecovery -InterfaceIndex @(19) } $script:taskError
Assert-Equal $script:actions @('register', 'start', 'stop-task', 'unregister') 'Failed task was not cleaned up'
Assert-Equal $script:pipeDisposed $true 'Failed recovery leaked its diagnostic pipe'

Reset-Case
$script:startDenied = $true
Assert-Rejected { Invoke-OpenRadRadminRecovery -InterfaceIndex @(19) } 'Task start failure was ignored'
Assert-Equal $script:actions @('register', 'start', 'stop-task', 'unregister') 'Task start failure leaked a task'

Reset-Case
$script:registrationDenied = $true
Assert-Rejected { Invoke-OpenRadRadminRecovery -InterfaceIndex @(19) } 'Registration failure was ignored'
Assert-Equal $script:actions @() 'Cleaned up a task that was not registered'
Assert-Equal $script:pipeDisposed $true 'Registration failure leaked its diagnostic pipe'

# Execute the actual encoded catch/IPC code in disposable PowerShell children.
# Linux uses byte mode for these local test pipes; production uses Windows
# message mode and an explicit current-user/SYSTEM ACL.
foreach ($message in @('synthetic adapter failure: ошибка, não, lỗi', ('x' * 1100))) {
    $name = [regex]::Match($script:workerCommand, 'OpenRad-RadminRecovery-[a-f0-9]{32}').Value
    $pipe = [IO.Pipes.NamedPipeServerStream]::new($name, [IO.Pipes.PipeDirection]::In, 1, [IO.Pipes.PipeTransmissionMode]::Byte, [IO.Pipes.PipeOptions]::Asynchronous)
    $child = [Diagnostics.Process]::new()
    try {
        $connection = $pipe.BeginWaitForConnection($null, $null)
        Assert-Equal (& $originalResultReader -Pipe $pipe -Connection $connection) '' 'Unconnected diagnostic pipe did not return immediately'
        $worker = ${function:Invoke-OpenRadRadminRecoveryWorker}.ToString()
        $command = $script:workerCommand.Replace($worker, ('param([uint32[]]$InterfaceIndex); throw ''' + $message + ''''))
        $child.StartInfo.FileName = [Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
        $child.StartInfo.Arguments = '-NoLogo -NoProfile -NonInteractive -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
        $child.StartInfo.UseShellExecute = $false
        $child.StartInfo.RedirectStandardError = $true
        $child.Start() | Out-Null
        if (-not $child.WaitForExit(5000)) { $child.Kill(); throw 'Diagnostic worker did not exit within five seconds.' }
        Assert-Equal $child.ExitCode 1 'Encoded worker did not report failure'
        $expected = $message.Substring(0, [Math]::Min(1000, $message.Length))
        Assert-Equal (& $originalResultReader -Pipe $pipe -Connection $connection) $expected 'Actual worker error was lost, truncated incorrectly, or decoded incorrectly'
        Assert-Equal $child.StandardError.ReadToEnd() '' 'Encoded worker leaked CLIXML instead of reporting through IPC'
    } finally { $pipe.Dispose(); $child.Dispose() }
}

$child = [Diagnostics.Process]::new()
try {
    $command = [IO.File]::ReadAllText($source) + "`nthrow 'synthetic top-level recovery failure'"
    $child.StartInfo.FileName = [Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
    $child.StartInfo.Arguments = '-NoLogo -NoProfile -NonInteractive -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $child.StartInfo.UseShellExecute = $false
    $child.StartInfo.RedirectStandardError = $true
    $child.Start() | Out-Null
    if (-not $child.WaitForExit(5000)) { $child.Kill(); throw 'Top-level error test did not exit within five seconds.' }
    Assert-Equal $child.ExitCode 1 'Top-level failure did not set the process exit status'
    Assert-Equal $child.StandardError.ReadToEnd().Trim() 'synthetic top-level recovery failure' 'Top-level errors still contain PowerShell CLIXML'
} finally { $child.Dispose() }

Write-Host 'PASS: 27 synthetic Radmin cases, including two real encoded-worker/pipe error round trips, plain top-level errors, bounded adapter verification, pipe cleanup and script syntax. No Windows services, processes, adapters, or scheduled tasks were changed.'
