# Headless setup-script regressions with synthetic adapters and mocked OS cmdlets.
# Runs on Linux PowerShell; it neither accesses Windows registry nor installs TAP.
$ErrorActionPreference = 'Stop'
$setupScript = Join-Path $PSScriptRoot 'setup-adapter.ps1'
$targetGuid = [Guid]'01234567-89ab-cdef-0123-456789abcdef'
$otherGuid = [Guid]'fedcba98-7654-3210-fedc-ba9876543210'
$targetDriverKey = '{4D36E972-E325-11CE-BFC1-08002BE10318}\0001'
function New-Adapter([string]$Name, [Guid]$Guid) {
    # MSFT_NetAdapter declares InterfaceGuid as a string, including brace format.
    return [PSCustomObject]@{ Name = $Name; InterfaceGuid = $Guid.ToString('B').ToUpperInvariant(); AdminStatus = 'Down' }
}
function Reset-Case([object[]]$Adapters, [string]$Component = 'tap0901') {
    $global:OpenRadTestAdapters = $Adapters
    $global:OpenRadTestActions = @()
    $global:OpenRadTestReads = 0
    $global:OpenRadTestDriver = [PSCustomObject]@{ NetCfgInstanceId = $targetGuid.ToString('B'); ComponentId = $Component }
}
function Get-NetAdapter {
    param([switch]$IncludeHidden)
    $global:OpenRadTestReads++
    if ($global:OpenRadTestReads -gt 8) { throw 'Setup did not recognize the synthetic Windows-format adapter GUID.' }
    return $global:OpenRadTestAdapters
}
function Get-ChildItem {
    param([string]$LiteralPath)
    throw [System.UnauthorizedAccessException]::new('The network class contains protected registry subkeys; enumerate only the selected device through SetupAPI.')
}
function Get-ItemProperty {
    param([string]$LiteralPath)
    if ($LiteralPath -ne ('HKLM:\SYSTEM\CurrentControlSet\Control\Class\' + $targetDriverKey)) { throw 'Setup read an unrelated registry subtree.' }
    return $global:OpenRadTestDriver
}
function Rename-NetAdapter {
    param([Parameter(ValueFromPipeline = $true)]$InputObject, [string]$NewName, [switch]$Confirm)
    process {
        $global:OpenRadTestActions += ('rename:' + ([Guid]$InputObject.InterfaceGuid))
        $InputObject.Name = $NewName
    }
}
function Enable-NetAdapter {
    param([Parameter(ValueFromPipeline = $true)]$InputObject, [switch]$Confirm)
    process { $global:OpenRadTestActions += ('enable:' + ([Guid]$InputObject.InterfaceGuid)); $InputObject.AdminStatus = 'Up' }
}
function Assert-Equal($Actual, $Expected, [string]$Reason) {
    if (($Actual -join '|') -ne ($Expected -join '|')) { throw $Reason }
}
function Assert-Rejected([string]$Reason) {
    $failed = $false
    try { & $setupScript -AdapterGuid $targetGuid -DriverKey $targetDriverKey } catch { $failed = $true }
    if (-not $failed -or $global:OpenRadTestActions.Count -ne 0) { throw $Reason }
}

Reset-Case -Adapters @((New-Adapter 'Ethernet 7' $targetGuid), (New-Adapter 'Other VPN' $otherGuid))
& $setupScript -AdapterGuid $targetGuid -DriverKey $targetDriverKey
Assert-Equal $global:OpenRadTestActions @(('rename:' + $targetGuid), ('enable:' + $targetGuid)) 'Setup did not change exactly its selected adapter.'
Assert-Equal $global:OpenRadTestAdapters[1].Name 'Other VPN' 'Setup renamed another VPN adapter.'

Reset-Case -Adapters @((New-Adapter 'OpenRad' $targetGuid), (New-Adapter 'Other VPN' $otherGuid))
& $setupScript -AdapterGuid $targetGuid -DriverKey $targetDriverKey
Assert-Equal $global:OpenRadTestActions @(('enable:' + $targetGuid)) 'Repeat configuration unnecessarily renamed another adapter.'

Reset-Case -Adapters @((New-Adapter 'Ethernet 7' $targetGuid), (New-Adapter 'OpenRad' $otherGuid))
Assert-Rejected 'An existing OpenRad name collision was not rejected before mutation.'

Reset-Case -Adapters @((New-Adapter 'Ethernet 7' $targetGuid)) -Component 'other-driver'
Assert-Rejected 'A non-TAP driver was not rejected before mutation.'

Reset-Case -Adapters @((New-Adapter 'Ethernet 7' $targetGuid), (New-Adapter 'Duplicate' $targetGuid))
Assert-Rejected 'A duplicate adapter GUID was not rejected before mutation.'

# Lookup, collision checks and the post-rename lookup must compare GUID values,
# regardless of braces/casing or whether a mocked provider returns a Guid object.
foreach ($format in @('D', 'N', 'typed')) {
    $adapter = New-Adapter 'OpenRad' $targetGuid
    if ($format -eq 'typed') { $adapter.InterfaceGuid = $targetGuid }
    else { $adapter.InterfaceGuid = $targetGuid.ToString($format) }
    Reset-Case -Adapters @($adapter, (New-Adapter 'Other VPN' $otherGuid))
    & $setupScript -AdapterGuid $targetGuid -DriverKey $targetDriverKey
    Assert-Equal $global:OpenRadTestActions @(('enable:' + $targetGuid)) ('GUID representation was not normalized: ' + $format)
}

Reset-Case -Adapters @((New-Adapter 'Ethernet 7' $targetGuid), [PSCustomObject]@{ Name = 'Unrelated'; InterfaceGuid = 'invalid-guid' })
& $setupScript -AdapterGuid $targetGuid -DriverKey $targetDriverKey
Assert-Equal $global:OpenRadTestActions @(('rename:' + $targetGuid), ('enable:' + $targetGuid)) 'An invalid GUID on an unrelated adapter disrupted setup.'

Reset-Case -Adapters @((New-Adapter 'Ethernet 7' $targetGuid))
$global:OpenRadTestDriver.NetCfgInstanceId = $otherGuid.ToString('B')
Assert-Rejected 'A driver key for a different adapter was not rejected before mutation.'

Reset-Case -Adapters @((New-Adapter 'Ethernet 7' $targetGuid))
$failed = $false
try { & $setupScript -AdapterGuid $targetGuid -DriverKey ($targetDriverKey + '\Properties') } catch { $failed = $true }
if (-not $failed -or $global:OpenRadTestActions.Count -ne 0) { throw 'An unexpected driver-key path was not rejected before mutation.' }

foreach ($name in @('setup-adapter.ps1', 'Launch-CLI.ps1', 'Test-Windows.ps1', 'Test-SetupLogic.ps1')) {
    $tokens = $null
    $errors = $null
    [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name), [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors.Count -ne 0) { throw ('PowerShell syntax errors in ' + $name + ': ' + ($errors -join '; ')) }
}
Write-Host 'PASS: eleven synthetic adapter setup cases, including protected sibling registry keys and Windows string GUID formats, and syntax of all four PowerShell scripts. No Windows OS cmdlets or driver APIs were executed.'
