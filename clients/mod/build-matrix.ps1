param(
    [string[]]$MinecraftVersions = @('1.20.6','1.21','1.21.1','1.21.4')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$approved = @('1.20.6','1.21','1.21.1','1.21.4')
$versionMatches = [regex]::Matches((Get-Content -LiteralPath (Join-Path $root 'build.gradle.kts') -Raw), 'version\s*=\s*"([^"]+)"')
if ($versionMatches.Count -ne 1) { throw 'Cannot identify the current mod version in build.gradle.kts' }
$version = $versionMatches[0].Groups[1].Value
New-Item -ItemType Directory -Force -Path (Join-Path $root 'release') | Out-Null
foreach($target in $MinecraftVersions){
    if($target -notin $approved){
        throw "No validated profile for Fabric Minecraft $target. Validated: $($approved -join ', ')"
    }
}
foreach($target in $MinecraftVersions){
    Write-Output "=== Building OpenRad Fabric Minecraft $target ==="
    $versionArgument = "-PopenradMinecraftVersion=$target"
    & ".\gradlew.bat" --no-daemon $versionArgument :core:test :fabric:build :fabric:test --console=plain
    if($LASTEXITCODE -ne 0){throw "Gradle failed for Minecraft $target"}
    $built = Join-Path $root "fabric\build\libs\fabric-$version.jar"
    & python ".\tools\verify-version-jar.py" $built $target $version
    if($LASTEXITCODE -ne 0){throw "JAR verification failed for $target"}
    $out = Join-Path $root "release\OpenRadClientMod-$version-fabric-$target.jar"
    Copy-Item -LiteralPath $built -Destination $out -Force
    Write-Output "Delivered: $out"
}
Write-Output "All requested versions built and checked. No Minecraft server or VPN process touched."
