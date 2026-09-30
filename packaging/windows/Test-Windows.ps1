# Local CLI smoke test only: synthetic credentials, loopback server, no TAP.
param([string]$InstallDirectory = (Join-Path $env:ProgramFiles 'OpenRad'))
$ErrorActionPreference = 'Stop'
$binary = Join-Path $InstallDirectory 'openrad.exe'
$work = Join-Path $env:TEMP ('openrad-windows-smoke-' + [guid]::NewGuid().ToString('N'))
$profile = Join-Path $work 'state'
$started = $false
function Invoke-OpenRadJson {
    param([string[]]$Arguments)
    $text = & $binary --data-dir $profile --json @Arguments
    if ($LASTEXITCODE -ne 0) { throw ('openrad failed: ' + ($Arguments -join ' ')) }
    return ($text | ConvertFrom-Json)
}
try {
    New-Item -ItemType Directory -Path $work | Out-Null
    & $binary --version
    if ($LASTEXITCODE -ne 0) { throw 'CLI version failed' }
    $desktop = Start-Process -FilePath (Join-Path $InstallDirectory 'openrad-desktop.exe') -ArgumentList '--version' -PassThru -Wait
    if ($desktop.ExitCode -ne 0) { throw 'Desktop version failed' }
    & $binary --help | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'CLI help failed' }
    $source = Join-Path $work 'synthetic-identity.json'
    $identity = '{"format":"openrad-identity-v1","rid":123,"vip":"26.0.0.5","node_name":"synthetic","address":"00000000000000000000000000000000","credential":"010203040506","server_address":"127.0.0.1"}'
    [IO.File]::WriteAllText($source, $identity, [Text.UTF8Encoding]::new($false))
    $imported = Invoke-OpenRadJson -Arguments @('init', '--identity', $source)
    if ($imported.data.rid -ne 123) { throw 'Identity import did not preserve RID' }
    $first = Invoke-OpenRadJson -Arguments @('start', '--no-tap')
    $started = $true
    if (-not $first.data.started) { throw 'First service start failed' }
    $second = Invoke-OpenRadJson -Arguments @('start', '--no-tap')
    if ($second.data.started) { throw 'Second service start created another service' }
    $status = Invoke-OpenRadJson -Arguments @('status')
    if ($status.data.rid -ne 123) { throw 'Detached service identity mismatch' }
    if ($status.data.phase -notin @('connecting','reconnecting')) { throw 'Unexpected offline service phase' }
    $reply = & $binary --data-dir $profile --json join 'Synthetic offline network'
    if ($LASTEXITCODE -eq 0) { throw 'Offline join unexpectedly succeeded' }
    if (($reply | ConvertFrom-Json).ok) { throw 'Offline join did not report failure' }
    Invoke-OpenRadJson -Arguments @('stop') | Out-Null
    $started = $false
    if ((Invoke-OpenRadJson -Arguments @('status')).data.phase -ne 'stopped') { throw 'Service did not stop' }
    Invoke-OpenRadJson -Arguments @('start', '--no-tap') | Out-Null
    $started = $true
    Invoke-OpenRadJson -Arguments @('stop') | Out-Null
    $started = $false
    Write-Host 'PASS: executable startup, synthetic import, named-pipe control, detached service, offline rejection, stop and restart.'
    Write-Host 'TAP installation, desktop runtime and live networking still need the tests in docs/windows.md.'
} finally {
    if ($started) { & $binary --data-dir $profile --json stop | Out-Null }
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
