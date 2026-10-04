# Synthetic regressions for the embedded recovery. Never executes Windows cmdlets.
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../../src/platform/windows_radmin.ps1'
$tokens = $null; $errors = $null
[Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count) { throw ('Recovery syntax: ' + ($errors -join '; ')) }
. $source
$originalWorker = ${function:Invoke-OpenRadRadminRecoveryWorker}
$originalAdmin = ${function:Invoke-OpenRadRadminAdminRecovery}
$originalSystem = ${function:Invoke-OpenRadRadminSystemRecovery}
$originalResultReader = ${function:Read-OpenRadRadminResult}
if (-not [IO.File]::ReadAllText($source).Contains('$security.SetAccessRuleProtection($true, $false)')) { throw 'Diagnostic pipe ACL is not protected.' }
$script:passed = 0
function Assert-Equal($Actual, $Expected, [string]$Reason) {
    if (($Actual -join '|') -ne ($Expected -join '|')) { throw ($Reason + ': ' + ($Actual -join '|')) }
}
function Assert-ErrorContains([scriptblock]$Action, [string]$Expected) {
    try { & $Action } catch {
        if (-not $_.Exception.Message.Contains($Expected)) { throw ('Missing error detail: ' + $_.Exception.Message) }
        return
    }
    throw ('Expected failure: ' + $Expected)
}
function Assert-Rejected([scriptblock]$Action, [string]$Reason) {
    try { & $Action } catch { return }
    throw $Reason
}
function New-Adapter([uint32]$Index, [string]$Description = 'Famatech Radmin VPN Ethernet Adapter') {
    return [PSCustomObject]@{ Name = ('Adapter ' + $Index); InterfaceIndex = $Index; InterfaceGuid = [Guid]::NewGuid().ToString('B'); InterfaceDescription = $Description; InterfaceAdminStatus = 1 }
}
function Reset-Case {
    $env:PSModulePath = [IO.Path]::Combine([Environment]::SystemDirectory, 'WindowsPowerShell\v1.0\Modules')
    $script:adapters = @((New-Adapter 19), (New-Adapter 20 'TAP-Windows Adapter V9'))
    $script:devices = @($script:adapters | ForEach-Object { [PSCustomObject]@{ GUID = $_.InterfaceGuid; Description = $_.InterfaceDescription; PNPDeviceID = ('ROOT\NET\' + $_.InterfaceIndex); ConfigManagerErrorCode = 0 } })
    $script:services = @(
        [PSCustomObject]@{ Name = 'RadminVpnService'; PathName = '"C:\Program Files\Radmin VPN\RvControlSvc.exe" /service' },
        [PSCustomObject]@{ Name = 'OtherService'; PathName = 'C:\Other\server.exe' }
    )
    $script:processes = @([PSCustomObject]@{ Id = 123; Name = 'RvControlSvc' }, [PSCustomObject]@{ Id = 124; Name = 'RvRvpnGui' }, [PSCustomObject]@{ Id = 125; Name = 'OtherApp' })
    $script:actions = @(); $script:stopResult = 0; $script:stopThrows = $false
    $script:killDenied = $false; $script:nativeKillDenied = $false; $script:disappearAtKill = $false
    $script:disableMethod = 'net'; $script:pnpHidden = $false; $script:reenable = $false; $script:verificationReads = 0
    $script:respawn = $false; $script:replaceAtKill = $false; $script:adminFailures = 0; $script:systemFails = $false
    $script:polls = 0; $script:taskResult = 0; $script:startDenied = $false; $script:registrationDenied = $false; $script:startRace = $false
    $script:pipeDisposed = $false; $script:taskError = 'synthetic worker failure: adapter access denied'
}
function Test-Case([string]$Name, [scriptblock]$Action) {
    Reset-Case
    try { & $Action; $script:passed++ } catch { throw ($Name + ': ' + $_.Exception.Message + '; ' + $_.ScriptStackTrace) }
}
function Get-NetAdapter {
    param([switch]$IncludeHidden)
    Assert-Equal $env:PSModulePath ([IO.Path]::Combine([Environment]::SystemDirectory, 'WindowsPowerShell\v1.0\Modules')) 'Untrusted module lookup'
    if ($script:actions -match '^disable:') {
        $script:verificationReads++
        if ($script:reenable) { $script:adapters[0].InterfaceAdminStatus = 1 }
        if ($script:respawn -and $script:verificationReads -eq 5) {
            $script:processes += [PSCustomObject]@{ Id = 126; Name = 'RvRvpnGui' }
            $script:adapters[0].InterfaceAdminStatus = 1
        }
    }
    return $script:adapters | Where-Object { -not ($script:pnpHidden -and $_.InterfaceAdminStatus -eq 2) }
}
function Get-CimInstance {
    param([string]$ClassName, [int]$OperationTimeoutSec)
    if ($OperationTimeoutSec -ne 2) { throw 'CIM operation lacks its timeout.' }
    switch ($ClassName) {
        'Win32_Service' { return $script:services }
        'Win32_NetworkAdapter' { return $script:devices }
        default { throw 'Unexpected CIM class.' }
    }
}
function Invoke-CimMethod {
    param($InputObject, [string]$MethodName, [int]$OperationTimeoutSec)
    if ($OperationTimeoutSec -ne 2) { throw 'CIM method lacks its timeout.' }
    if ($MethodName -eq 'StopService') {
        $script:actions += ('stop:' + $InputObject.Name)
        if ($script:stopThrows) { throw 'CIM service denied' }
        return [PSCustomObject]@{ ReturnValue = $script:stopResult }
    }
    if ($MethodName -eq 'Disable') {
        $script:actions += 'disable:cim'
        if ($script:disableMethod -eq 'cim') { $script:adapters[0].InterfaceAdminStatus = 2; return [PSCustomObject]@{ ReturnValue = 0 } }
        return [PSCustomObject]@{ ReturnValue = 5 }
    }
    throw 'Service configuration changed or unexpected method.'
}
function Get-Process {
    param([string[]]$Name, $ErrorAction)
    if (@($Name | Where-Object { $_ -notin @('RvControlSvc', 'RvRvpnGui') }).Count) { throw 'Unrelated process enumeration.' }
    return $script:processes | Where-Object { $_.Name -in $Name }
}
function Stop-Process {
    param($InputObject, [switch]$Force, $ErrorAction)
    if (-not $Force -or $InputObject.Name -notin @('RvControlSvc', 'RvRvpnGui')) { throw 'Unsafe process termination.' }
    $script:actions += ('kill:' + $InputObject.Name)
    if ($script:replaceAtKill) { $script:adapters[0].InterfaceDescription = 'Ethernet' }
    if ($script:killDenied) { throw 'process denied' }
    $script:processes = @($script:processes | Where-Object { $_.Id -ne $InputObject.Id })
    if ($script:disappearAtKill) { throw 'process exited' }
}
function Disable-NetAdapter {
    param([Parameter(ValueFromPipeline = $true)]$InputObject, [switch]$Confirm)
    process {
        $script:actions += 'disable:net'
        if ($script:disableMethod -ne 'net') { throw 'net adapter denied' }
        $InputObject.InterfaceAdminStatus = 2
    }
}
function Disable-PnpDevice {
    param([string]$InstanceId, [switch]$Confirm, $ErrorAction)
    Assert-Equal $InstanceId 'ROOT\NET\19' 'Disabled another PnP device'
    $script:actions += 'disable:pnp'
    if ($script:disableMethod -ne 'pnp') { throw 'PnP denied' }
    $script:adapters[0].InterfaceAdminStatus = 2; $script:devices[0].ConfigManagerErrorCode = 22
}
function Invoke-TestNative {
    param([string]$Name, [string[]]$Argument)
    switch ($Name) {
        'sc.exe' { Assert-Equal $Argument @('stop', 'RadminVpnService') 'Stopped another service'; $script:actions += 'sc:stop' }
        'taskkill.exe' {
            if ($Argument[0] -ne '/F' -or $Argument[1] -ne '/IM' -or $Argument[2] -notin @('RvControlSvc.exe','RvRvpnGui.exe') -or $Argument.Count -ne 3) { throw 'Unsafe taskkill arguments.' }
            $script:actions += ('taskkill:' + $Argument[2])
            if (-not $script:nativeKillDenied) { $script:processes = @($script:processes | Where-Object { ($_.Name + '.exe') -ne $Argument[2] }) }
        }
        'netsh.exe' {
            Assert-Equal $Argument @('interface','set','interface',('name=' + $script:adapters[0].Name),'admin=disabled') 'Unsafe netsh arguments'
            $script:actions += 'disable:netsh'
            if ($script:disableMethod -eq 'netsh') { $script:adapters[0].InterfaceAdminStatus = 2 }
        }
        'pnputil.exe' {
            if ($Argument.Count -eq 3) { Assert-Equal $Argument @('/disable-device','ROOT\NET\19','/force') 'Unsafe forced PnPUtil arguments' }
            else { Assert-Equal $Argument @('/disable-device','ROOT\NET\19') 'Unsafe PnPUtil arguments' }
            $script:actions += $(if ($Argument.Count -eq 3) { 'disable:pnputil-force' } else { 'disable:pnputil' })
            if ($script:disableMethod -eq 'pnputil' -or ($script:disableMethod -eq 'pnputil-force' -and $Argument.Count -eq 3)) { $script:adapters[0].InterfaceAdminStatus = 2; $script:devices[0].ConfigManagerErrorCode = 22 }
        }
        default { throw 'Unexpected native executable.' }
    }
}
function Start-Sleep { param([int]$Milliseconds); if ($Milliseconds -notin @(200,250)) { throw 'Unexpected wait interval.' } }
# Intercept only the native child-process helper. The target/service/process
# validation and five-method fallback code are the exact embedded worker.
$ast = [Management.Automation.Language.Parser]::ParseInput($originalWorker.ToString(), [ref]$tokens, [ref]$errors)
$native = $ast.Find({ param($node); $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-Native' }, $true)
$mock = 'function Invoke-Native { param([string]$Name, [string[]]$Argument); Invoke-TestNative -Name $Name -Argument $Argument }'
Set-Item function:Invoke-OpenRadRadminRecoveryWorker ([scriptblock]::Create($originalWorker.ToString().Replace($native.Extent.Text, $mock)))
function Run-Worker { Invoke-OpenRadRadminRecoveryWorker -AdapterGuid @($script:adapters[0].InterfaceGuid) }
Test-Case 'service and GUI are both terminated before adapter disable' {
    Run-Worker
    Assert-Equal $script:actions @('stop:RadminVpnService','sc:stop','kill:RvRvpnGui','kill:RvControlSvc','disable:net') 'Incorrect recovery ordering'
    Assert-Equal $script:processes.Name @('OtherApp') 'Unrelated process was killed'
    Assert-Equal $script:adapters[1].InterfaceAdminStatus 1 'Another adapter was disabled'
}
foreach ($method in @('cim','netsh','pnp','pnputil')) {
    Test-Case ('fallback ' + $method) { $script:disableMethod = $method; Run-Worker; if ($script:actions -notcontains ('disable:' + $method)) { throw 'Fallback was skipped.' } }
}
Test-Case 'forced PnPUtil fallback' { $script:disableMethod = 'pnputil-force'; Run-Worker; if ($script:actions -notcontains 'disable:pnputil-force') { throw 'Forced PnPUtil skipped.' } }
Test-Case 'stop refused still force-kills both images' { $script:stopResult = 5; Run-Worker; Assert-Equal $script:processes.Name @('OtherApp') 'Protected stop skipped kill' }
Test-Case 'unsupported stop still force-kills both images' { $script:stopResult = 1; Run-Worker }
Test-Case 'CIM stop exception still force-kills both images' { $script:stopThrows = $true; Run-Worker }
Test-Case 'taskkill fallback for denied Stop-Process' { $script:killDenied = $true; Run-Worker; Assert-Equal ($script:actions | Where-Object { $_ -like 'taskkill:*' }) @('taskkill:RvRvpnGui.exe','taskkill:RvControlSvc.exe') 'Native fallback missed an image' }
Test-Case 'persistent process denial blocks setup' { $script:killDenied = $true; $script:nativeKillDenied = $true; Assert-ErrorContains { Run-Worker } 'process denied' }
Test-Case 'all disable methods fail with bounded diagnostics' { $script:disableMethod = 'none'; Assert-ErrorContains { Run-Worker } 'Adapter method 3'; Assert-Equal ($script:actions | Where-Object { $_ -eq 'disable:pnputil' }).Count 6 'Unbounded retries' }
Test-Case 'exited process race is harmless' { $script:disappearAtKill = $true; Run-Worker }
Test-Case 'reenabled adapter does not report success' { $script:reenable = $true; Assert-ErrorContains { Run-Worker } 'did not remain stopped/disabled' }
Test-Case 'restarted GUI/adapter is killed and disabled again' { $script:respawn = $true; Run-Worker; if ($script:actions -notcontains 'kill:RvRvpnGui' -or ($script:actions | Where-Object { $_ -eq 'disable:net' }).Count -lt 2) { throw 'Restart was ignored.' } }
foreach ($method in @('pnp','pnputil')) {
    Test-Case ('hidden PnP-disabled device ' + $method) { $script:disableMethod = $method; $script:pnpHidden = $true; Run-Worker }
}
Test-Case 'device replacement prevents further mutation' { $script:replaceAtKill = $true; Assert-Rejected { Run-Worker } 'Replacement accepted'; if ($script:actions -match '^disable:') { throw 'Disabled a replacement.' } }
Test-Case 'wrong PnP GUID never gets disabled' { $script:disableMethod = 'pnp'; $script:devices[0].GUID = [Guid]::NewGuid(); Assert-ErrorContains { Run-Worker } 'device identity changed'; if ($script:actions -contains 'disable:pnp') { throw 'Wrong device disabled.' } }
Test-Case 'quoted service argument is not an executable match' { $script:services[0].PathName = '"C:\Other\server.exe" --helper C:\Radmin\RvControlSvc.exe'; Run-Worker; if ($script:actions -like 'stop:*') { throw 'Argument-only match was stopped.' } }
Test-Case 'unquoted service argument is not an executable match' { $script:services[0].PathName = 'C:\Other\server.exe --helper C:\Radmin\RvControlSvc.exe'; Run-Worker; if ($script:actions -like 'stop:*') { throw 'Argument-only match was stopped.' } }
Test-Case 'no Radmin devices/processes is a successful no-op' { $script:processes = @(); $script:services = @(); Invoke-OpenRadRadminRecoveryWorker -AdapterGuid @(); Assert-Equal $script:actions @() 'No-op mutated adapters' }
Test-Case 'multiple official adapters stay selected by GUID' { $script:adapters += New-Adapter 21; Invoke-OpenRadRadminRecoveryWorker -AdapterGuid @($script:adapters[0].InterfaceGuid,$script:adapters[2].InterfaceGuid); Assert-Equal $script:adapters.InterfaceAdminStatus @(2,1,2) 'Not all official targets recovered' }
# Privilege escalation is also exercised without spawning an elevated process.
function Invoke-OpenRadRadminAdminRecovery {
    param([Guid[]]$AdapterGuid)
    $script:actions += 'admin'
    $script:selectedGuids = $AdapterGuid
    if ($script:adminFailures -gt 0) { $script:adminFailures--; throw 'synthetic admin denied' }
}
function Invoke-OpenRadRadminSystemRecovery {
    param([Guid[]]$AdapterGuid)
    $script:actions += 'system'
    Assert-Equal $AdapterGuid $script:selectedGuids 'SYSTEM targets changed'
    if ($script:systemFails) { throw 'synthetic SYSTEM denied' }
}
Test-Case 'admin succeeds without unnecessary SYSTEM' { Invoke-OpenRadRadminRecovery -InterfaceIndex @(19); Assert-Equal $script:actions @('admin') 'Wrong context sequence' }
Test-Case 'admin denied escalates to SYSTEM' { $script:adminFailures = 1; Invoke-OpenRadRadminRecovery -InterfaceIndex @(19); Assert-Equal $script:actions @('admin','system') 'SYSTEM skipped' }
Test-Case 'SYSTEM denied retries administrator' { $script:adminFailures = 1; $script:systemFails = $true; Invoke-OpenRadRadminRecovery -InterfaceIndex @(19); Assert-Equal $script:actions @('admin','system','admin') 'Final admin skipped' }
Test-Case 'all contexts denied preserve diagnostics' { $script:adminFailures = 2; $script:systemFails = $true; Assert-ErrorContains { Invoke-OpenRadRadminRecovery -InterfaceIndex @(19) } 'synthetic SYSTEM denied' }
Test-Case 'installer discovers even addressless/down official adapters' { $script:adapters[0].InterfaceAdminStatus = 2; Invoke-OpenRadRadminRecovery -Discover; Assert-Equal $script:selectedGuids @([Guid]$script:adapters[0].InterfaceGuid) 'Discovery omitted official adapter' }
Test-Case 'installer handles Radmin absent' { $script:adapters = @($script:adapters[1]); Invoke-OpenRadRadminRecovery -Discover; Assert-Equal $script:selectedGuids @() 'Selected unrelated adapter' }
Test-Case 'numbered official description is accepted' { $script:adapters[0].InterfaceDescription += ' #2'; Invoke-OpenRadRadminRecovery -InterfaceIndex @(19); Assert-Equal $script:actions @('admin') 'Numbered description rejected' }
Test-Case 'unrelated index fails before any context' { Assert-Rejected { Invoke-OpenRadRadminRecovery -InterfaceIndex @(20) } 'Unrelated accepted'; Assert-Equal $script:actions @() 'Unrelated context started' }
Test-Case 'missing index fails before any context' { Assert-Rejected { Invoke-OpenRadRadminRecovery -InterfaceIndex @(999) } 'Missing accepted'; Assert-Equal $script:actions @() 'Missing context started' }
# Restore real embedded source before testing encoded SYSTEM commands and IPC.
Set-Item function:Invoke-OpenRadRadminRecoveryWorker $originalWorker
Set-Item function:Invoke-OpenRadRadminAdminRecovery $originalAdmin
Set-Item function:Invoke-OpenRadRadminSystemRecovery $originalSystem
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
    param([string]$Execute, [string]$Argument, [string]$WorkingDirectory)
    Assert-Equal $WorkingDirectory ([Environment]::SystemDirectory) 'SYSTEM task has an untrusted working directory'
    Assert-Equal $Execute 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' 'Task does a PATH lookup'
    $encoded = ($Argument -split ' -EncodedCommand ')[1]
    $command = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($encoded))
    $script:workerCommand = $command
    if (-not $command.Contains('Stop-Process') -or -not $command.Contains('-AdapterGuid @(')) { throw 'Encoded task does not contain the fixed worker/targets.' }
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
Invoke-OpenRadRadminSystemRecovery -AdapterGuid @($script:adapters[0].InterfaceGuid)
Assert-Equal $script:actions @('register', 'start', 'stop-task', 'unregister') 'Successful task was not cleaned up'
Assert-Equal $script:polls 4 'Task success was accepted before its worker finished'
Assert-Equal $script:pipeDisposed $true 'Successful recovery leaked its diagnostic pipe'

Reset-Case
$script:startRace = $true
Invoke-OpenRadRadminSystemRecovery -AdapterGuid @($script:adapters[0].InterfaceGuid)
Assert-Equal $script:polls 4 'An old Ready state and a new LastRunTime falsely signaled completion'
Assert-Equal $script:actions @('register', 'start', 'stop-task', 'unregister') 'Start-race task was not cleaned up'

Reset-Case
$script:taskResult = 1
Assert-ErrorContains { Invoke-OpenRadRadminSystemRecovery -AdapterGuid @($script:adapters[0].InterfaceGuid) } $script:taskError
Assert-Equal $script:actions @('register', 'start', 'stop-task', 'unregister') 'Failed task was not cleaned up'
Assert-Equal $script:pipeDisposed $true 'Failed recovery leaked its diagnostic pipe'

Reset-Case
$script:startDenied = $true
Assert-Rejected { Invoke-OpenRadRadminSystemRecovery -AdapterGuid @($script:adapters[0].InterfaceGuid) } 'Task start failure was ignored'
Assert-Equal $script:actions @('register', 'start', 'stop-task', 'unregister') 'Task start failure leaked a task'

Reset-Case
$script:registrationDenied = $true
Assert-Rejected { Invoke-OpenRadRadminSystemRecovery -AdapterGuid @($script:adapters[0].InterfaceGuid) } 'Registration failure was ignored'
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
        $command = $script:workerCommand.Replace($worker, ('param([Guid[]]$AdapterGuid); throw ''' + $message + ''''))
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

Test-Case 'maximum SYSTEM target list stays below command limits' {
    Invoke-OpenRadRadminSystemRecovery -AdapterGuid @(1..16 | ForEach-Object { [Guid]::NewGuid() })
}
# Exercise the exact stdin bootstrap used by Rust with a program larger than
# Windows' command-line limit. Only definitions and synthetic UTF-8 checks run.
$child = [Diagnostics.Process]::new()
try {
    $bootstrap = '[Console]::InputEncoding = [Text.UTF8Encoding]::new($false); & ([scriptblock]::Create([Console]::In.ReadToEnd()))'
    $child.StartInfo.FileName = [Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
    $child.StartInfo.Arguments = '-NoLogo -NoProfile -NonInteractive -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($bootstrap))
    $child.StartInfo.UseShellExecute = $false
    $child.StartInfo.RedirectStandardInput = $true
    $child.StartInfo.RedirectStandardOutput = $true
    $child.StartInfo.RedirectStandardError = $true
    $child.Start() | Out-Null
    $command = [IO.File]::ReadAllText($source) + "`n# " + ('x' * 35000) + "`nif ('não'.Length -ne 3 -or 'ошибка'.Length -ne 6) { exit 2 }; [Console]::Write('stdin-ok')"
    $child.StandardInput.Write($command)
    $child.StandardInput.Close()
    if (-not $child.WaitForExit(5000)) { $child.Kill(); throw 'Stdin bootstrap timed out.' }
    Assert-Equal $child.ExitCode 0 ('Stdin program failed: ' + $child.StandardError.ReadToEnd())
    Assert-Equal $child.StandardOutput.ReadToEnd() 'stdin-ok' 'Stdin program or UTF-8 was corrupted'
    $script:passed++
} finally { $child.Dispose() }
$script:passed += 8 # Five scheduler paths, two actual child/IPC round trips, one top-level error.
Write-Host ('PASS: ' + $script:passed + ' synthetic recovery cases, five adapter disable paths, service/GUI force termination, admin/SYSTEM fallback, bounded retries, protected pipe cleanup and real child/IPC diagnostics. No Windows services, processes, adapters or scheduled tasks were changed.')
