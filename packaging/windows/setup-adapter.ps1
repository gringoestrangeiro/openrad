param(
    [Parameter(Mandatory = $true)][Guid]$AdapterGuid,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('(?i)^\{4d36e972-e325-11ce-bfc1-08002be10318\}\\[0-9]{4}$')]
    [string]$DriverKey
)
$ErrorActionPreference = 'Stop'
# MSFT_NetAdapter.InterfaceGuid is a string. Compare parsed GUID values so a
# Windows "{GUID}" string does not compare against Guid.ToString() without braces.
function Test-AdapterGuid([object]$Value) {
    $parsed = [Guid]::Empty
    return ([Guid]::TryParse([string]$Value, [ref]$parsed) -and $parsed -eq $AdapterGuid)
}
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    $matches = @(Get-NetAdapter -IncludeHidden | Where-Object { Test-AdapterGuid $_.InterfaceGuid })
    if ($matches.Count -eq 1) { break }
    if ($matches.Count -gt 1) { throw 'Duplicate network adapter GUID during setup.' }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)
if ($matches.Count -ne 1) { throw 'Windows has not finished creating the TAP adapter. Restart Windows and run setup again.' }
$adapter = $matches[0]
# The native worker obtains this one device's SPDRP_DRIVER property through
# SetupAPI. Enumerating all class keys can touch protected siblings and fail
# under ordinary administrator elevation. Read only the exact selected key.
$driverPath = 'HKLM:\SYSTEM\CurrentControlSet\Control\Class\' + $DriverKey
$driver = Get-ItemProperty -LiteralPath $driverPath
if (-not (Test-AdapterGuid $driver.NetCfgInstanceId) -or $driver.ComponentId -ne 'tap0901') {
    throw 'The selected adapter is not an official TAP-Windows6 device.'
}
$collision = @(Get-NetAdapter -IncludeHidden | Where-Object { $_.Name -eq 'OpenRad' -and -not (Test-AdapterGuid $_.InterfaceGuid) })
if ($collision.Count -ne 0) { throw 'Another adapter already uses the name OpenRad. Rename it and run setup again.' }
if ($adapter.Name -ne 'OpenRad') { $adapter | Rename-NetAdapter -NewName 'OpenRad' -Confirm:$false }
$adapter = Get-NetAdapter -IncludeHidden | Where-Object { Test-AdapterGuid $_.InterfaceGuid }
$adapter | Enable-NetAdapter -Confirm:$false
