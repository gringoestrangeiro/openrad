[CmdletBinding()]
param(
    [ValidateSet('Cli', 'Desktop')][string]$Target = 'Cli',
    [switch]$System,
    [string]$PsExecPath,
    [string]$InstallDir,
    [ValidateSet('auto', 'opengl', 'wgpu', 'software')][string]$Renderer = 'software'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

function Quote-PS([string]$Value) { return "'" + $Value.Replace("'", "''") + "'" }
function Encode-PS([string]$Script) {
    return [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Script))
}

try {
    $powershell = Join-Path $PSHOME 'powershell.exe'
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        $restart = '& ' + (Quote-PS $PSCommandPath) + ' -Target ' + (Quote-PS $Target) + ' -Renderer ' + (Quote-PS $Renderer)
        if ($System) { $restart += ' -System' }
        if ($PsExecPath) { $restart += ' -PsExecPath ' + (Quote-PS $PsExecPath) }
        if ($InstallDir) { $restart += ' -InstallDir ' + (Quote-PS $InstallDir) }
        Start-Process -FilePath $powershell -Verb RunAs -ArgumentList @('-NoLogo', '-NoProfile', '-NoExit', '-EncodedCommand', (Encode-PS $restart))
        return
    }

    if (-not $InstallDir) {
        if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'openrad.exe')) {
            $InstallDir = $PSScriptRoot
        } else {
            $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, [Microsoft.Win32.RegistryView]::Registry64)
            try {
                $key = $base.OpenSubKey('SOFTWARE\OpenRad')
                if ($null -ne $key) {
                    try { $InstallDir = [string]$key.GetValue('InstallLocation') } finally { $key.Dispose() }
                }
            } finally { $base.Dispose() }
        }
    }
    if (-not $InstallDir) { throw 'Install OpenRad-Setup.exe first, or supply -InstallDir.' }
    $InstallDir = (Resolve-Path -LiteralPath $InstallDir).ProviderPath
    $binary = if ($Target -eq 'Desktop') { 'openrad-desktop.exe' } else { 'openrad.exe' }
    $exe = Join-Path $InstallDir $binary
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw "Missing application: $exe" }

    $profileOption = ''
    if ($System) {
        # Only this explicitly requested mode downloads PsExec. Protect its
        # cache before extraction and verify Microsoft's executable signature.
        $root = Join-Path $env:ProgramData 'OpenRad\system-diagnostic'
        New-Item -ItemType Directory -Path $root -Force | Out-Null
        $acl = New-Object Security.AccessControl.DirectorySecurity
        $acl.SetSecurityDescriptorSddlForm('O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)')
        Set-Acl -LiteralPath $root -AclObject $acl
        $logs = Join-Path $root 'logs'
        $profile = Join-Path $root $Target.ToLowerInvariant()
        $profileOption = ' --data-dir ' + (Quote-PS $profile)
        if (-not $PsExecPath) {
            $tools = Join-Path $root 'pstools'
            $PsExecPath = Join-Path $tools 'PsExec64.exe'
            if (-not (Test-Path -LiteralPath $PsExecPath -PathType Leaf)) {
                Write-Host 'Downloading Microsoft PsTools for the requested SYSTEM diagnostic mode...'
                [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
                $zip = Join-Path $root 'PSTools.zip'
                Invoke-WebRequest -Uri 'https://download.sysinternals.com/files/PSTools.zip' -OutFile $zip -UseBasicParsing
                Expand-Archive -LiteralPath $zip -DestinationPath $tools -Force
                Remove-Item -LiteralPath $zip
            }
        }
        $PsExecPath = (Resolve-Path -LiteralPath $PsExecPath).ProviderPath
        $signature = Get-AuthenticodeSignature -LiteralPath $PsExecPath
        if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation(?:,|$)') {
            throw 'PsExec must have a valid Microsoft signature. Use the official PsTools download.'
        }
        Write-Host 'SYSTEM mode uses a separate diagnostic profile and credential store.'
        Write-Host 'Close the ordinary desktop and stop its CLI service before testing this mode.'
        Write-Host "Diagnostic profile: $profile"
        # Do not silently accept the Sysinternals license; PsExec displays it.
    } else {
        $logs = Join-Path $env:LOCALAPPDATA 'OpenRad\logs'
    }

    $body = '$ErrorActionPreference = ''Stop''; $env:RUST_BACKTRACE = ''full''; ' +
        '$env:OPENRAD_LOG_DIR = ' + (Quote-PS $logs) + '; ' +
        'Set-Location -LiteralPath ' + (Quote-PS $InstallDir) + '; ' +
        'New-Item -ItemType Directory -Path $env:OPENRAD_LOG_DIR -Force | Out-Null; ' +
        'Write-Host (''Account: '' + [Security.Principal.WindowsIdentity]::GetCurrent().Name); ' +
        'Write-Host (''Logs: '' + $env:OPENRAD_LOG_DIR); '
    if ($Target -eq 'Cli') {
        $body += '$global:OpenRadDiagnosticExe = ' + (Quote-PS $exe) + '; '
        $body += 'function global:openrad { & $global:OpenRadDiagnosticExe' + $profileOption + ' @args; '
        $body += '$result = $LASTEXITCODE; $code = [BitConverter]::ToUInt32([BitConverter]::GetBytes([int]$result), 0); '
        $body += 'Add-Content -LiteralPath (Join-Path $env:OPENRAD_LOG_DIR ''cli-launcher.log'') -Value ((Get-Date -Format o) + '' exit_code='' + $result + '' exit_hex=0x'' + $code.ToString(''X8'')); '
        $body += 'if ($result -ne 0) { Write-Host (''OpenRad exit code: '' + $result + '' (0x'' + $code.ToString(''X8'') + '')'') -ForegroundColor Red } }; '
        $body += 'Write-Host ''Use openrad init/start/status/retry-interface/stop. Only safe startup metadata is logged.''; openrad status; '
        if ($System) {
            $body += 'Write-Host ''Initialize/import an identity in this separate profile before openrad start.''; '
        }
    } else {
        $body += '& ' + (Quote-PS $exe) + ' --renderer ' + (Quote-PS $Renderer) + $profileOption + '; '
    }
    $encoded = Encode-PS $body
    if ($System) {
        $session = [Diagnostics.Process]::GetCurrentProcess().SessionId
        & $PsExecPath -s -i $session -w $InstallDir $powershell -NoLogo -NoProfile -NoExit -EncodedCommand $encoded
        if ($LASTEXITCODE -ne 0) { throw "SYSTEM launcher exited with code $LASTEXITCODE" }
    } else {
        & $powershell -NoLogo -NoProfile -NoExit -EncodedCommand $encoded
    }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    Read-Host 'Press Enter to close'
    exit 1
}
