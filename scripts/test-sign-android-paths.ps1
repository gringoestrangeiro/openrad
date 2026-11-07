# Synthetic regression tests: never invoke signing tools or request a password.
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$signer = Join-Path $repo 'scripts\sign-android-local.ps1'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('openrad-signing-paths-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $temp
$passed = 0
try {
    foreach ($outputKind in @('apk', 'manifest')) {
        foreach ($inputKind in @('Keystore', 'UnsignedApk', 'ReferenceApk')) {
            $caseDir = Join-Path $temp ($outputKind + '-' + $inputKind)
            $null = New-Item -ItemType Directory -Path $caseDir
            $output = Join-Path $caseDir 'signed.apk'
            $inputs = @{
                Keystore = Join-Path $caseDir 'synthetic.jks'
                UnsignedApk = Join-Path $caseDir 'unsigned.apk'
                ReferenceApk = Join-Path $caseDir 'reference.apk'
            }
            $inputs[$inputKind] = if ($outputKind -eq 'apk') { $output } else { Join-Path $caseDir 'SHA256SUMS' }
            foreach ($path in $inputs.Values) { [IO.File]::WriteAllText($path, 'SYNTHETIC-INPUT-NO-KEY') }
            $reason = ''
            try { & $signer @inputs -Alias synthetic -OutputApk $output -SdkRoot (Join-Path $temp 'no-sdk') -WarningAction SilentlyContinue }
            catch { $reason = $_.Exception.Message }
            if ($reason -notmatch 'must not be an input file') { throw "$outputKind/$inputKind was not rejected by the input collision guard: $reason" }
            foreach ($path in $inputs.Values) {
                if ([IO.File]::ReadAllText($path) -ne 'SYNTHETIC-INPUT-NO-KEY') { throw "Input changed: $path" }
            }
            $passed++
        }
    }
    $realDir = Join-Path $temp 'real-output'
    $aliasDir = Join-Path $temp 'junction-output'
    $null = New-Item -ItemType Directory -Path $realDir
    $null = New-Item -ItemType Junction -Path $aliasDir -Target $realDir
    try {
        $key = Join-Path $realDir 'SHA256SUMS'
        $unsigned = Join-Path $temp 'junction-unsigned.apk'
        [IO.File]::WriteAllText($key, 'SYNTHETIC-INPUT-NO-KEY')
        [IO.File]::WriteAllText($unsigned, 'SYNTHETIC-INPUT-NO-KEY')
        $reason = ''
        try { & $signer -Keystore $key -UnsignedApk $unsigned -Alias synthetic -OutputApk (Join-Path $aliasDir 'signed.apk') -SdkRoot (Join-Path $temp 'no-sdk') -WarningAction SilentlyContinue }
        catch { $reason = $_.Exception.Message }
        if ($reason -notmatch 'must not use a reparse point') { throw "Junction alias not rejected: $reason" }
        if ([IO.File]::ReadAllText($key) -ne 'SYNTHETIC-INPUT-NO-KEY') { throw 'Junction input changed' }
        $passed++
        $reason = ''
        try { & $signer -Keystore (Join-Path $aliasDir 'SHA256SUMS') -UnsignedApk $unsigned -Alias synthetic -OutputApk (Join-Path $realDir 'signed.apk') -SdkRoot (Join-Path $temp 'no-sdk') -WarningAction SilentlyContinue }
        catch { $reason = $_.Exception.Message }
        if ($reason -notmatch 'must not use a reparse point') { throw "Inverse junction alias not rejected: $reason" }
        if ([IO.File]::ReadAllText($key) -ne 'SYNTHETIC-INPUT-NO-KEY') { throw 'Inverse junction input changed' }
        $passed++
    } finally {
        # Remove the junction itself, never recursively follow its target.
        [IO.Directory]::Delete($aliasDir)
    }
    Write-Output "PASS: $passed collision/alias cases; all synthetic inputs preserved."
} finally {
    # $temp is a newly created GUID directory directly below the system temp directory.
    if ([IO.Path]::GetFullPath($temp).StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $temp -Recurse -Force
    }
}
