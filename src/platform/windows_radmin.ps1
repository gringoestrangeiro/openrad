# Embedded in OpenRad; never loaded from a writable installation at runtime.
$ProgressPreference = 'SilentlyContinue'
$ErrorActionPreference = 'Stop'
trap { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
function Invoke-OpenRadRadminRecoveryWorker {
    param([Guid[]]$AdapterGuid)
    $env:PSModulePath = [IO.Path]::Combine([Environment]::SystemDirectory, 'WindowsPowerShell\v1.0\Modules')
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    $failures = [Collections.Generic.List[string]]::new()
    function Add-Failure([string]$Message) {
        if ($failures.Count -lt 32) { $failures.Add($Message.Substring(0, [Math]::Min(200, $Message.Length))) }
    }
    function Test-Official([string]$Description) {
        return $Description -match '^Famatech Radmin VPN Ethernet Adapter(?: #[0-9]+)?$'
    }
    function Get-Device([Guid]$Guid) {
        $devices = @(Get-CimInstance -ClassName Win32_NetworkAdapter -OperationTimeoutSec 2 | Where-Object {
            $_.GUID -and ([Guid]$_.GUID) -eq $Guid
        })
        if ($devices.Count -ne 1 -or -not (Test-Official $devices[0].Description)) {
            throw 'The official Radmin VPN device identity changed.'
        }
        return $devices[0]
    }
    function Get-Target([Guid]$Guid) {
        $rows = @(Get-NetAdapter -IncludeHidden | Where-Object {
            $_.InterfaceGuid -and ([Guid]$_.InterfaceGuid) -eq $Guid
        })
        if ($rows.Count -eq 1 -and (Test-Official $rows[0].InterfaceDescription)) { return $rows[0] }
        # A PnP-disabled device can disappear from MSFT_NetAdapter. Require the
        # same GUID and official description plus CM_PROB_DISABLED (22).
        if ($rows.Count -eq 0) {
            $device = Get-Device $Guid
            if ($device.ConfigManagerErrorCode -eq 22) {
                return [PSCustomObject]@{ InterfaceGuid = $Guid; InterfaceAdminStatus = 2 }
            }
        }
        throw 'The official Radmin VPN adapter changed or disappeared during recovery.'
    }
    function Invoke-Native([string]$Name, [string[]]$Argument) {
        $process = [Diagnostics.Process]::new()
        try {
            $process.StartInfo.FileName = [IO.Path]::Combine([Environment]::SystemDirectory, $Name)
            $process.StartInfo.WorkingDirectory = [Environment]::SystemDirectory
            # Direct CreateProcess argument quoting; never use cmd.exe or PATH.
            $process.StartInfo.Arguments = (($Argument | ForEach-Object {
                '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
            }) -join ' ')
            $process.StartInfo.UseShellExecute = $false
            $process.StartInfo.CreateNoWindow = $true
            $process.StartInfo.RedirectStandardOutput = $true
            $process.StartInfo.RedirectStandardError = $true
            $process.Start() | Out-Null
            $stdout = $process.StandardOutput.ReadToEndAsync()
            $stderr = $process.StandardError.ReadToEndAsync()
            if (-not $process.WaitForExit(2000)) {
                $process.Kill()
                throw ($Name + ' timed out')
            }
            if ($process.ExitCode -ne 0) { Add-Failure ($Name + ' returned ' + $process.ExitCode) }
        } catch { Add-Failure $_.Exception.Message } finally { $process.Dispose() }
    }
    function Stop-Radmin {
        try {
            $services = @(Get-CimInstance -ClassName Win32_Service -OperationTimeoutSec 2 | Where-Object {
                $path = ([string]$_.PathName).Trim()
                $exe = if ($path.StartsWith('"')) { ($path -split '"', 3)[1] }
                       else { [regex]::Match($path, '(?i)^.+?\.exe(?=\s|$)').Value }
                $exe -match '(?i)(?:^|\\)RvControlSvc\.exe$'
            })
            foreach ($service in $services) {
                try {
                    $result = Invoke-CimMethod -InputObject $service -MethodName StopService -OperationTimeoutSec 2
                    if ($result.ReturnValue -notin @(0, 6)) { Add-Failure ('StopService ' + $service.Name + ' returned ' + $result.ReturnValue) }
                } catch { Add-Failure ('StopService: ' + $_.Exception.Message) }
                Invoke-Native 'sc.exe' @('stop', $service.Name)
            }
        } catch { Add-Failure ('Service discovery: ' + $_.Exception.Message) }
        foreach ($name in @('RvRvpnGui', 'RvControlSvc')) {
            foreach ($process in @(Get-Process -Name $name -ErrorAction SilentlyContinue)) {
                try { Stop-Process -InputObject $process -Force -ErrorAction Stop }
                catch { Add-Failure ('Stop-Process ' + $name + ': ' + $_.Exception.Message) }
            }
            # Terminate even when SCM refused StopService. Restrict the native
            # fallback to these exact images, including other Windows sessions.
            if (Get-Process -Name $name -ErrorAction SilentlyContinue) {
                Invoke-Native 'taskkill.exe' @('/F', '/IM', ($name + '.exe'))
            }
        }
    }
    function Disable-Target([Guid]$Guid) {
        for ($method = 0; $method -lt 5; $method++) {
            $current = Get-Target $Guid
            if ($current.InterfaceAdminStatus -eq 2) { return }
            try {
                switch ($method) {
                    0 { $current | Disable-NetAdapter -Confirm:$false -ErrorAction Stop }
                    1 {
                        $device = Get-Device $Guid
                        $result = Invoke-CimMethod -InputObject $device -MethodName Disable -OperationTimeoutSec 2
                        if ($result.ReturnValue -ne 0) { throw ('Win32_NetworkAdapter.Disable returned ' + $result.ReturnValue) }
                    }
                    2 {
                        $sameName = @(Get-NetAdapter -IncludeHidden | Where-Object { $_.Name -eq $current.Name })
                        if ($sameName.Count -ne 1 -or ([Guid]$sameName[0].InterfaceGuid) -ne $Guid) { throw 'Ambiguous adapter name for netsh.' }
                        Invoke-Native 'netsh.exe' @('interface', 'set', 'interface', ('name=' + $current.Name), 'admin=disabled')
                    }
                    3 {
                        $device = Get-Device $Guid
                        if (-not $device.PNPDeviceID) { throw 'Radmin PnP instance ID is unavailable.' }
                        Disable-PnpDevice -InstanceId $device.PNPDeviceID -Confirm:$false -ErrorAction Stop
                    }
                    4 {
                        $device = Get-Device $Guid
                        if (-not $device.PNPDeviceID) { throw 'Radmin PnP instance ID is unavailable.' }
                        Invoke-Native 'pnputil.exe' @('/disable-device', $device.PNPDeviceID)
                        if ((Get-Target $Guid).InterfaceAdminStatus -ne 2) {
                            $device = Get-Device $Guid
                            if (-not $device.PNPDeviceID) { throw 'Radmin PnP instance ID is unavailable.' }
                            # /force is supported on Windows 11 22H2+. Earlier
                            # versions reject it; the selected instance is fixed.
                            Invoke-Native 'pnputil.exe' @('/disable-device', $device.PNPDeviceID, '/force')
                        }
                    }
                }
            } catch { Add-Failure ('Adapter method ' + $method + ': ' + $_.Exception.Message) }
        }
    }
    if ($AdapterGuid.Count -gt 16) { throw 'Too many official Radmin VPN adapters.' }
    foreach ($guid in $AdapterGuid) { $null = Get-Target $guid }
    Stop-Radmin
    foreach ($guid in $AdapterGuid) { Disable-Target $guid }
    $stable = 0
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        Start-Sleep -Milliseconds 200
        $running = @(Get-Process -Name 'RvRvpnGui', 'RvControlSvc' -ErrorAction SilentlyContinue)
        $disabled = $true
        foreach ($guid in $AdapterGuid) {
            if ((Get-Target $guid).InterfaceAdminStatus -ne 2) { $disabled = $false }
        }
        if ($running.Count -eq 0 -and $disabled) { $stable++ } else { $stable = 0 }
        if ($stable -ge 10) { return }
        if (($running.Count -ne 0 -or -not $disabled) -and $attempt % 10 -eq 0) {
            Stop-Radmin
            foreach ($guid in $AdapterGuid) { Disable-Target $guid }
        }
    }
    throw ('Radmin VPN processes/adapters did not remain stopped/disabled; ' + ($failures -join '; '))
}

# Run the same immutable worker in a short-lived administrator child before
# escalating to SYSTEM. Bound the entire child, including hung Windows cmdlets.
function Invoke-OpenRadRadminAdminRecovery {
    param([Guid[]]$AdapterGuid)
    $worker = ${function:Invoke-OpenRadRadminRecoveryWorker}.ToString()
    $guids = ($AdapterGuid | ForEach-Object { "'" + $_.ToString('D') + "'" }) -join ','
    $command = 'try { & { ' + $worker + ' } -AdapterGuid @(' + $guids + '); exit 0 } catch { $m = $_.Exception.Message; [Console]::Error.WriteLine($m.Substring(0, [Math]::Min(1000, $m.Length))); exit 1 }'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $process = [Diagnostics.Process]::new()
    try {
        $process.StartInfo.FileName = [IO.Path]::Combine([Environment]::SystemDirectory, 'WindowsPowerShell\v1.0\powershell.exe')
        $process.StartInfo.WorkingDirectory = [Environment]::SystemDirectory
        $process.StartInfo.Arguments = '-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand ' + $encoded
        if ($process.StartInfo.Arguments.Length -gt 30000) { throw 'Recovery command exceeds the Windows command limit.' }
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.CreateNoWindow = $true
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        $process.Start() | Out-Null
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {
            $process.Kill()
            throw 'Administrator Radmin VPN recovery timed out.'
        }
        if ($process.ExitCode -ne 0) { throw ('Administrator Radmin VPN recovery failed: ' + $stderr.Result.Trim()) }
    } finally { $process.Dispose() }
}

function Invoke-OpenRadRadminRecovery {
    param([uint32[]]$InterfaceIndex, [switch]$Discover)
    $env:PSModulePath = [IO.Path]::Combine([Environment]::SystemDirectory, 'WindowsPowerShell\v1.0\Modules')
    $all = @(Get-NetAdapter -IncludeHidden)
    $selected = @()
    if ($Discover) {
        $selected = @($all | Where-Object { $_.InterfaceDescription -match '^Famatech Radmin VPN Ethernet Adapter(?: #[0-9]+)?$' })
    } else {
        if ($InterfaceIndex.Count -eq 0) { throw 'No official Radmin VPN adapter was selected.' }
        foreach ($index in $InterfaceIndex) {
            $candidates = @($all | Where-Object { $_.InterfaceIndex -eq $index })
            if ($candidates.Count -ne 1 -or $candidates[0].InterfaceDescription -notmatch '^Famatech Radmin VPN Ethernet Adapter(?: #[0-9]+)?$') {
                throw 'The selected official Radmin VPN adapter changed before recovery.'
            }
            $selected += $candidates[0]
        }
    }
    $guids = @($selected | ForEach-Object { [Guid]$_.InterfaceGuid } | Select-Object -Unique)
    try { Invoke-OpenRadRadminAdminRecovery -AdapterGuid $guids; return }
    catch { $adminError = $_.Exception.Message }
    try { Invoke-OpenRadRadminSystemRecovery -AdapterGuid $guids; return }
    catch { $systemError = $_.Exception.Message }
    # A SYSTEM failure may have partially disabled the device. Try the user's
    # administrator context once more; always keep both errors if it fails.
    try { Invoke-OpenRadRadminAdminRecovery -AdapterGuid $guids }
    catch { throw ($adminError + '; ' + $systemError + '; final administrator attempt: ' + $_.Exception.Message) }
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

function Invoke-OpenRadRadminSystemRecovery {
    param([Guid[]]$AdapterGuid)
    $ErrorActionPreference = 'Stop'
    $taskName = 'OpenRad-RadminRecovery-' + [Guid]::NewGuid().ToString('N')
    $worker = ${function:Invoke-OpenRadRadminRecoveryWorker}.ToString()
    $command = @'
try { & { WORKER } -AdapterGuid @(GUIDS); exit 0 } catch {
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
    $command = $command.Replace('WORKER', $worker).Replace('GUIDS', (($AdapterGuid | ForEach-Object { "'" + $_.ToString('D') + "'" }) -join ',')).Replace('PIPE_NAME', $taskName)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $powershell = Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe'
    if ($encoded.Length -gt 30000) { throw 'SYSTEM recovery command exceeds the Windows command limit.' }
    $action = New-ScheduledTaskAction -Execute $powershell -WorkingDirectory ([Environment]::SystemDirectory) -Argument ('-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand ' + $encoded)
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
