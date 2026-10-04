# Embedded in OpenRad; never loaded from a writable installation at runtime.
$ProgressPreference = 'SilentlyContinue'
$ErrorActionPreference = 'Stop'
trap { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
function Invoke-OpenRadRadminRecoveryWorker {
    param([uint32[]]$InterfaceIndex)
    # The scheduled SYSTEM worker starts a separate PowerShell process. Reset
    # its module path too, before resolving the network/service cmdlets.
    $env:PSModulePath = [IO.Path]::Combine([Environment]::SystemDirectory, 'WindowsPowerShell\v1.0\Modules')
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'
    $description = 'Famatech Radmin VPN Ethernet Adapter'
    $targets = @()
    foreach ($index in $InterfaceIndex) {
        $adapter = @(Get-NetAdapter -IncludeHidden | Where-Object { $_.InterfaceIndex -eq $index })
        if ($adapter.Count -ne 1 -or $adapter[0].InterfaceDescription -ine $description) {
            throw 'The selected official Radmin VPN adapter changed before recovery.'
        }
        $targets += $adapter[0]
    }
    if ($targets.Count -eq 0) { throw 'No official Radmin VPN adapter was selected.' }

    # Stop the service before force-killing its process so SCM recovery does not
    # restart it and immediately reenable the driver. Match its executable path;
    # do not assume a localized display name or stop other Radmin products.
    $services = @(Get-CimInstance -ClassName Win32_Service | Where-Object {
        $path = ([string]$_.PathName).Trim()
        $executable = if ($path.StartsWith('"')) {
            ($path -split '"', 3)[1]
        } else {
            [regex]::Match($path, '(?i)^.+?\.exe(?=\s|$)').Value
        }
        $executable -match '(?i)(?:^|\\)RvControlSvc\.exe$'
    })
    $stopErrors = @()
    $stopping = @()
    foreach ($service in $services) {
        try {
            $result = Invoke-CimMethod -InputObject $service -MethodName StopService
            # 0 = success, 6 = already stopped. A protected service can refuse
            # even SYSTEM. Still try disabling the adapter, but never kill that
            # service and trigger its failure-restart policy.
            if ($result.ReturnValue -in @(0, 6)) {
                $stopping += $service
            } else {
                $stopErrors += ('StopService ' + $service.Name + ' returned ' + $result.ReturnValue)
            }
        } catch { $stopErrors += ('StopService ' + $service.Name + ': ' + $_.Exception.Message) }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $running = @($stopping | Where-Object {
            (Get-Service -Name $_.Name).Status -ne 'Stopped'
        })
        if ($running.Count -eq 0) { break }
        if ([DateTime]::UtcNow -ge $deadline) {
            $stopErrors += 'The official Radmin VPN service did not stop within ten seconds.'
            break
        }
        Start-Sleep -Milliseconds 200
    } while ($true)
    if ($stopErrors.Count -eq 0) {
        foreach ($process in @(Get-Process -Name 'RvControlSvc' -ErrorAction SilentlyContinue)) {
            try { Stop-Process -InputObject $process -Force -ErrorAction Stop }
            catch {
                # A service can exit between enumeration and termination.
                if (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) {
                    $stopErrors += ('Stop-Process RvControlSvc.exe: ' + $_.Exception.Message)
                }
            }
        }
        if (Get-Process -Name 'RvControlSvc' -ErrorAction SilentlyContinue) {
            $stopErrors += 'RvControlSvc.exe is still running.'
        }
    }
    foreach ($adapter in $targets) {
        # Revalidate the GUID and description after stopping the service.
        $current = @(Get-NetAdapter -IncludeHidden | Where-Object {
            $_.InterfaceGuid -and ([string]$_.InterfaceGuid).Trim('{}') -ieq ([string]$adapter.InterfaceGuid).Trim('{}')
        })
        if ($current.Count -ne 1 -or $current[0].InterfaceDescription -ine $description) {
            throw 'The official Radmin VPN adapter changed during recovery.'
        }
        try { $current[0] | Disable-NetAdapter -Confirm:$false -ErrorAction Stop }
        catch {
            throw ('Disable-NetAdapter: ' + $_.Exception.Message + '; ' + ($stopErrors -join '; '))
        }
    }
    # A successful disable request alone does not prove the conflict is gone.
    # Check administrative state (not media/link state) by GUID, since Windows
    # may change the interface index during disabling. Observe it for two
    # seconds so an immediate service-driven reenable cannot report success.
    $stable = 0
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        Start-Sleep -Milliseconds 200
        $disabled = $true
        foreach ($adapter in $targets) {
            $current = @(Get-NetAdapter -IncludeHidden | Where-Object {
                $_.InterfaceGuid -and ([string]$_.InterfaceGuid).Trim('{}') -ieq ([string]$adapter.InterfaceGuid).Trim('{}')
            })
            if ($current.Count -ne 1 -or $current[0].InterfaceDescription -ine $description) {
                throw 'The official Radmin VPN adapter changed while verifying recovery.'
            }
            if ($current[0].InterfaceAdminStatus -ne 2) { $disabled = $false }
        }
        if ($disabled) { $stable++ } else { $stable = 0 }
        if ($stable -ge 10) { return }
    }
    throw ('The official Radmin VPN adapter did not remain administratively disabled; ' + ($stopErrors -join '; '))
}

# Only SYSTEM and this elevated user can access the temporary diagnostic pipe.
# The worker never writes as SYSTEM to a path under a writable user directory.
function New-OpenRadRadminResultPipe {
    param([string]$Name)
    $security = [IO.Pipes.PipeSecurity]::new()
    $security.SetAccessRuleProtection($true, $false)
    foreach ($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $security.AddAccessRule([IO.Pipes.PipeAccessRule]::new($sid, [IO.Pipes.PipeAccessRights]::FullControl, [Security.AccessControl.AccessControlType]::Allow))
    }
    return [IO.Pipes.NamedPipeServerStream]::new($Name, [IO.Pipes.PipeDirection]::In, 1, [IO.Pipes.PipeTransmissionMode]::Message, [IO.Pipes.PipeOptions]::Asynchronous, 4096, 4096, $security)
}

function Read-OpenRadRadminResult {
    param($Pipe, $Connection)
    try {
        if (-not $Connection.IsCompleted) { return '' }
        $Pipe.EndWaitForConnection($Connection)
        $buffer = [byte[]]::new(4096)
        $read = $Pipe.ReadAsync($buffer, 0, $buffer.Length)
        if (-not $read.Wait(1000)) { return '' }
        return [Text.Encoding]::UTF8.GetString($buffer, 0, $read.Result)
    } catch { return 'Worker diagnostic pipe could not be read.' }
}

function Invoke-OpenRadRadminRecovery {
    param([uint32[]]$InterfaceIndex)
    $ErrorActionPreference = 'Stop'
    $taskName = 'OpenRad-RadminRecovery-' + [Guid]::NewGuid().ToString('N')
    $worker = ${function:Invoke-OpenRadRadminRecoveryWorker}.ToString()
    $command = @'
try { & { WORKER } -InterfaceIndex @(INDICES); exit 0 } catch {
    $message = $_.Exception.Message
    if ($message.Length -gt 1000) { $message = $message.Substring(0, 1000) }
    $pipe = $null
    try {
        $pipe = [IO.Pipes.NamedPipeClientStream]::new('.', 'PIPE_NAME', [IO.Pipes.PipeDirection]::Out)
        $pipe.Connect(1000)
        $bytes = [Text.Encoding]::UTF8.GetBytes($message)
        $pipe.Write($bytes, 0, $bytes.Length)
    } catch {} finally { if ($pipe) { $pipe.Dispose() } }
    exit 1
}
'@
    $command = $command.Replace('WORKER', $worker).Replace('INDICES', ($InterfaceIndex -join ',')).Replace('PIPE_NAME', $taskName)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $powershell = Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe'
    $action = New-ScheduledTaskAction -Execute $powershell -Argument ('-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand ' + $encoded)
    $principal = New-ScheduledTaskPrincipal -UserId 'S-1-5-18' -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Seconds 30) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $registered = $false
    $pipe = New-OpenRadRadminResultPipe -Name $taskName
    try {
        $connection = $pipe.BeginWaitForConnection($null, $null)
        Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Settings $settings | Out-Null
        $registered = $true
        Start-ScheduledTask -TaskName $taskName
        $deadline = [DateTime]::UtcNow.AddSeconds(40)
        do {
            $info = Get-ScheduledTaskInfo -TaskName $taskName
            $task = Get-ScheduledTask -TaskName $taskName
            # A just-registered task reports Ready/zero before its first run.
            # Wait for a real run and exclude queued/running scheduler results.
            # Read run info before state: a pre-start Ready snapshot must not
            # combine with a later LastRunTime and falsely signal completion.
            if ($info.LastRunTime.Year -gt 2000 -and $task.State -notin @('Running', 'Queued') -and $info.LastTaskResult -notin @(267009, 267011)) {
                if ($info.LastTaskResult -ne 0) {
                    $detail = Read-OpenRadRadminResult -Pipe $pipe -Connection $connection
                    throw ('SYSTEM Radmin VPN recovery failed; task result ' + $info.LastTaskResult + ': ' + $detail)
                }
                return
            }
            if ([DateTime]::UtcNow -ge $deadline) { throw 'SYSTEM Radmin VPN recovery timed out.' }
            Start-Sleep -Milliseconds 200
        } while ($true)
    } finally {
        try {
            if ($registered) {
                Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
                Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
            }
        } finally { $pipe.Dispose() }
    }
}
