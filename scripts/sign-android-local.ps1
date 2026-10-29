# Sign the OpenRad Android production APK on a trusted Windows PC.
# NEVER copy the original keystore/passwords into GitHub or commit them to git.
# Requires the original keystore, Android SDK Build Tools 34, and Java 17+.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Keystore,
    [Parameter(Mandatory = $true)][string]$Alias,
    [string]$UnsignedApk,
    [string]$ReferenceApk,
    [string]$SdkRoot,
    [string]$OutputApk,
    # Full git commit the unsigned APK must have been built from (README-FIRST.txt next to it).
    [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
if (-not $UnsignedApk) {
    $UnsignedApk = Join-Path $repo 'dist\android-local-signing\openrad-2.2.0-android-UNSIGNED.apk'
}
if (-not $OutputApk) {
    $OutputApk = Join-Path $repo 'dist\android-release-signed\openrad-2.2.0-android.apk'
}
if (-not (Test-Path -LiteralPath $UnsignedApk -PathType Leaf)) {
    throw "Unsigned APK missing: $UnsignedApk. Download the unsigned GitHub Actions artifact into dist/android-local-signing first."
}
if ($ExpectedCommit) {
    $note = Join-Path (Split-Path -Parent (Resolve-Path -LiteralPath $UnsignedApk).Path) 'README-FIRST.txt'
    $built = if (Test-Path -LiteralPath $note -PathType Leaf) {
        [regex]::Match((Get-Content -LiteralPath $note -Raw), 'Source commit: ([0-9a-fA-F]{40})')
    }
    if (-not $built -or -not $built.Success) {
        throw 'Cannot read the source commit from README-FIRST.txt beside the unsigned APK.'
    }
    if ($built.Groups[1].Value -ne $ExpectedCommit.Trim().ToLowerInvariant()) {
        throw "The unsigned APK was built from $($built.Groups[1].Value), not the expected commit $ExpectedCommit."
    }
} else {
    Write-Warning 'No -ExpectedCommit given: the APK is not verified against a release commit.'
}
if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
    throw 'Original signing keystore not found. Do not generate a new key: upgrades would be incompatible.'
}
# Move-Item -Force replaces whatever file -OutputApk names, so refuse every input
# (including the keystore) before any password is requested or used.
$fullOutput = [IO.Path]::GetFullPath($OutputApk)
if ([IO.Path]::GetExtension($fullOutput) -ne '.apk') {
    throw '-OutputApk must name a .apk file.'
}
foreach ($input in @($Keystore, $UnsignedApk, $ReferenceApk)) {
    if ($input -and [string]::Equals([IO.Path]::GetFullPath($input), $fullOutput, [StringComparison]::OrdinalIgnoreCase)) {
        throw "-OutputApk must not be an input file: $input"
    }
}
if (-not $SdkRoot) {
    foreach ($location in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
            (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), 'C:\Android\Sdk')) {
        if ($location -and (Test-Path -LiteralPath (Join-Path $location 'build-tools\34.0.0'))) {
            $SdkRoot = $location
            break
        }
    }
}
if (-not $SdkRoot) {
    throw 'Android SDK Build Tools 34.0.0 not found. Install Android SDK Platform/Build Tools 34 using Android Studio > SDK Manager, or supply -SdkRoot.'
}
$tools = Join-Path $SdkRoot 'build-tools\34.0.0'
$aapt = Join-Path $tools 'aapt.exe'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksignerJar = Join-Path $tools 'lib\apksigner.jar'
foreach ($tool in @($aapt, $zipalign, $apksignerJar)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required Android signing tool missing: $tool" }
}
$java = (Get-Command java.exe -ErrorAction Stop).Source

function Read-Package([string]$file) {
    $result = @(& $aapt dump badging $file)
    if ($LASTEXITCODE -ne 0 -or $result.Count -lt 1) { throw 'Could not inspect APK package manifest.' }
    return [string]$result[0]
}
function Read-Certificate([string]$file) {
    $result = @(& $java -jar $apksignerJar verify --verbose --print-certs $file 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed for $(Split-Path $file -Leaf)" }
    $raw = $result -join [Environment]::NewLine
    $match = [regex]::Match($raw, 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})')
    if (-not $match.Success) { throw "Unable to identify signing certificate for $(Split-Path $file -Leaf)" }
    if ($raw -notmatch '(?m)^Verified using v[23] scheme.*: true') {
        throw 'Modern v2/v3 Android signature was not verified.'
    }
    return $match.Groups[1].Value.ToLowerInvariant()
}
function Use-SecretText([Security.SecureString]$secret) {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$original = $null
$referenceTempDir = $null
$pendingSigned = $null
$aligned = $null
$storeSecret = $null
$keySecret = $null
$storeText = $null
$keyText = $null
try {
    $unsignedDetails = Read-Package $UnsignedApk
    if ($unsignedDetails -notmatch "name='com[.]openrad[.]mobile'" -or
        $unsignedDetails -notmatch "versionCode='7'" -or
        $unsignedDetails -notmatch "versionName='2[.]2[.]0'") {
        throw "The unsigned APK is not OpenRad com.openrad.mobile 2.2.0 (versionCode 7)."
    }
    $sumFile = Join-Path (Split-Path -Parent $UnsignedApk) 'SHA256SUMS'
    if (Test-Path -LiteralPath $sumFile -PathType Leaf) {
        $want = [regex]::Match((Get-Content -LiteralPath $sumFile -Raw), '^[0-9a-fA-F]{64}')
        if (-not $want.Success) { throw 'SHA256SUMS invalid.' }
        $got = (Get-FileHash -LiteralPath $UnsignedApk -Algorithm SHA256).Hash
        if ($got -ine $want.Value) { throw 'Unsigned APK checksum mismatch.' }
    }
    if (-not $ReferenceApk) {
        $referenceTempDir = Join-Path ([IO.Path]::GetTempPath()) ('openrad-cert-' + [guid]::NewGuid().ToString('N'))
        $null = New-Item -Path $referenceTempDir -ItemType Directory -Force
        $gh = Get-Command gh.exe -ErrorAction SilentlyContinue
        if (-not $gh) { throw 'GitHub CLI (gh) not found. Provide -ReferenceApk with the original official 2.0.1 APK.' }
        & $gh.Source release download v2.0.1 -R gringoestrangeiro/openrad -p 'openrad-2.0.1-android.apk' -D $referenceTempDir
        if ($LASTEXITCODE -ne 0) { throw 'Unable to retrieve the official APK used as the certificate reference.' }
        $ReferenceApk = Join-Path $referenceTempDir 'openrad-2.0.1-android.apk'
    }
    if (-not (Test-Path -LiteralPath $ReferenceApk -PathType Leaf)) {
        throw 'Official reference APK not found.'
    }
    $originalDetails = Read-Package $ReferenceApk
    if ($originalDetails -notmatch "name='com[.]openrad[.]mobile'" -or
        $originalDetails -notmatch "versionCode='3'") {
        throw 'The reference APK is not the expected official Android 2.0.1 release.'
    }
    $original = Read-Certificate $ReferenceApk
    Write-Output 'Reference: verified official v2.0.1 APK signature and package metadata.'
    Write-Output 'Candidate: verified unsigned v2.2.0 APK package metadata and optional SHA256.'

    $storeSecret = Read-Host 'Password for the ORIGINAL keystore (not shown)' -AsSecureString
    $keySecret = Read-Host 'Password for the ORIGINAL key alias (not shown)' -AsSecureString
    $storeText = Use-SecretText $storeSecret
    $keyText = Use-SecretText $keySecret

    $outDir = Split-Path -Parent $OutputApk
    $null = New-Item -Path $outDir -ItemType Directory -Force
    $pendingSigned = Join-Path $outDir ('.openrad-signing-' + [guid]::NewGuid().ToString('N') + '.apk')
    $aligned = Join-Path $outDir ('.openrad-zipalign-' + [guid]::NewGuid().ToString('N') + '.apk')
    & $zipalign -p -f 4 $UnsignedApk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Android zipalign failed.' }

    # Passwords are provided on standard input, NEVER in command-line arguments.
    $quote = '"'
    $argsText = @(
        '-jar', ($quote + $apksignerJar + $quote),
        'sign', '--ks', ($quote + (Resolve-Path -LiteralPath $Keystore).Path + $quote),
        '--ks-key-alias', ($quote + $Alias + $quote),
        '--ks-pass', 'stdin', '--key-pass', 'stdin',
        '--out', ($quote + $pendingSigned + $quote),
        ($quote + $aligned + $quote)
    ) -join ' '
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $java
    $start.Arguments = $argsText
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($start)
    try {
        $process.StandardInput.WriteLine($storeText)
        $process.StandardInput.WriteLine($keyText)
        $process.StandardInput.Close()
        $stdOut = $process.StandardOutput.ReadToEnd()
        $stdErr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw 'Android signing failed. Check the original keystore, alias and passwords locally; no signed release was produced.'
        }
    } finally { $process.Dispose() }

    $actualCert = Read-Certificate $pendingSigned
    if ($actualCert -ne $original) {
        throw 'SIGNING BLOCKED: certificate differs from official Android 2.0.1. The APK cannot safely update existing installations.'
    }
    $signedDetails = Read-Package $pendingSigned
    if ($signedDetails -notmatch "name='com[.]openrad[.]mobile'" -or
        $signedDetails -notmatch "versionCode='7'" -or
        $signedDetails -notmatch "versionName='2[.]2[.]0'") {
        throw 'Signed APK application ID or version mismatch.'
    }
    Move-Item -LiteralPath $pendingSigned -Destination $OutputApk -Force
    $pendingSigned = $null
    $digest = (Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $outDir 'SHA256SUMS'),
        ($digest + '  ' + (Split-Path $OutputApk -Leaf) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false)))
    Write-Output "SUCCESS: locally signed OpenRad 2.2.0 APK: $OutputApk"
    Write-Output 'SUCCESS: certificate SHA-256 matches official Android 2.0.1 release.'
    Write-Output "SHA-256: $digest"
}
finally {
    $storeText = $null
    $keyText = $null
    if ($storeSecret) { $storeSecret.Dispose() }
    if ($keySecret) { $keySecret.Dispose() }
    if ($pendingSigned -and (Test-Path -LiteralPath $pendingSigned)) {
        Remove-Item -LiteralPath $pendingSigned -Force
    }
    if ($aligned -and (Test-Path -LiteralPath $aligned)) {
        Remove-Item -LiteralPath $aligned -Force
    }
    if ($referenceTempDir -and (Test-Path -LiteralPath $referenceTempDir)) {
        Remove-Item -LiteralPath $referenceTempDir -Recurse -Force
    }
}
