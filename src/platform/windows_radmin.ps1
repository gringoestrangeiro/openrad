# Embedded in OpenRad; never loaded from a writable installation at runtime.
function Invoke-OpenRadRadminRecoveryWorker {
    param([uint32[]]$InterfaceIndex)
    $ErrorActionPreference = 'Stop'
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
    foreach ($service in $services) {
        $result = Invoke-CimMethod -InputObject $service -MethodName StopService
        # 0 = success, 6 = already stopped. Never kill a live service whose stop
        # request failed, since that can activate its failure-restart policy.
        if ($result.ReturnValue -notin @(0, 6)) { throw 'Cannot stop the official Radmin VPN service.' }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $running = @($services | Where-Object {
            (Get-Service -Name $_.Name).Status -ne 'Stopped'
        })
        if ($running.Count -eq 0) { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'The official Radmin VPN service did not stop.' }
        Start-Sleep -Milliseconds 200
    } while ($true)
    foreach ($process in @(Get-Process -Name 'RvControlSvc' -ErrorAction SilentlyContinue)) {
        try { Stop-Process -InputObject $process -Force -ErrorAction Stop }
        catch {
            # A service can exit between enumeration and termination.
            if (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) { throw }
        }
    }
    if (Get-Process -Name 'RvControlSvc' -ErrorAction SilentlyContinue) {
        throw 'RvControlSvc.exe is still running.'
    }
    foreach ($adapter in $targets) {
        # Revalidate the GUID and description after stopping the service.
        $current = @(Get-NetAdapter -IncludeHidden | Where-Object {
            $_.InterfaceGuid -and ([string]$_.InterfaceGuid).Trim('{}') -ieq ([string]$adapter.InterfaceGuid).Trim('{}')
        })
        if ($current.Count -ne 1 -or $current[0].InterfaceDescription -ine $description) {
            throw 'The official Radmin VPN adapter changed during recovery.'
        }
        $current[0] | Disable-NetAdapter -Confirm:$false -ErrorAction Stop
    }
}

function Invoke-OpenRadRadminRecovery {
    param([uint32[]]$InterfaceIndex)
    $ErrorActionPreference = 'Stop'
    $taskName = 'OpenRad-RadminRecovery-' + [Guid]::NewGuid().ToString('N')
    $worker = ${function:Invoke-OpenRadRadminRecoveryWorker}.ToString()
    $command = 'try { & { ' + $worker + ' } -InterfaceIndex @(' + ($InterfaceIndex -join ',') + '); exit 0 } catch { exit 1 }'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $powershell = Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe'
    $action = New-ScheduledTaskAction -Execute $powershell -Argument ('-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand ' + $encoded)
    $principal = New-ScheduledTaskPrincipal -UserId 'S-1-5-18' -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Seconds 30) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $registered = $false
    try {
        Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Settings $settings | Out-Null
        $registered = $true
        Start-ScheduledTask -TaskName $taskName
        $deadline = [DateTime]::UtcNow.AddSeconds(40)
        do {
            $task = Get-ScheduledTask -TaskName $taskName
            $info = Get-ScheduledTaskInfo -TaskName $taskName
            # A just-registered task reports Ready/zero before its first run.
            # Wait for a real run and exclude queued/running scheduler results.
            if ($info.LastRunTime.Year -gt 2000 -and $task.State -notin @('Running', 'Queued') -and $info.LastTaskResult -notin @(267009, 267011)) {
                if ($info.LastTaskResult -ne 0) { throw ('SYSTEM Radmin VPN recovery failed; task result ' + $info.LastTaskResult) }
                return
            }
            if ([DateTime]::UtcNow -ge $deadline) { throw 'SYSTEM Radmin VPN recovery timed out.' }
            Start-Sleep -Milliseconds 200
        } while ($true)
    } finally {
        if ($registered) {
            Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
            Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        }
    }
}
