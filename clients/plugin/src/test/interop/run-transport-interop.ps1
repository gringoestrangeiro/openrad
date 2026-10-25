param(
    [Parameter(Mandatory = $true)][string]$OpenRadRoot,
    [string]$OpenRadLibrary
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$canonicalRoot = (Resolve-Path -LiteralPath $OpenRadRoot).Path
$dependencies = Join-Path $canonicalRoot 'target/debug/deps'
if (-not $OpenRadLibrary) {
    $OpenRadLibrary = (Get-ChildItem -LiteralPath $dependencies -Filter 'libopenrad-*.rlib' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not $OpenRadLibrary) { throw 'Build the unmodified canonical Rust library first with cargo build --lib --locked.' }
$socketLibrary = (Get-ChildItem -LiteralPath $dependencies -Filter 'libsocket2-*.rlib' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
if (-not $socketLibrary) { throw 'The canonical Windows socket2 build artifact is required by the test harness.' }
$interopWork = Join-Path $projectRoot 'work/transport-interop'
New-Item -ItemType Directory -Path $interopWork -Force | Out-Null
$executable = Join-Path $interopWork 'openrad-interop.exe'
& rustc (Join-Path $PSScriptRoot 'transport.rs') --edition=2021 -C debuginfo=0 -C strip=debuginfo --extern "openrad=$OpenRadLibrary" --extern "socket2=$socketLibrary" -L "dependency=$dependencies" -o $executable
if ($LASTEXITCODE -ne 0) { throw 'Rust interop harness build failed.' }
Push-Location -LiteralPath $projectRoot
try {
    & ./gradlew.bat classes --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Java classes build failed.' }
    & javac -cp 'build/classes/java/main' -d $interopWork (Join-Path $PSScriptRoot 'TransportInterop.java')
    if ($LASTEXITCODE -ne 0) { throw 'Java interop harness build failed.' }
    & java -cp "build/classes/java/main;$interopWork" TransportInterop $executable
    if ($LASTEXITCODE -ne 0) { throw 'Java/Rust transport interoperability failed.' }
} finally { Pop-Location }
