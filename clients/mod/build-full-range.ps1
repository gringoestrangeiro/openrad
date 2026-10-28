param(
    [string[]]$Targets = @(
        '1.18.2','1.19','1.19.1','1.19.2','1.19.3','1.19.4','1.20.1','1.20.2','1.20.3','1.20.4','1.20.5','1.20.6',
        '1.21','1.21.1','1.21.2','1.21.3','1.21.4',
        '1.21.5','1.21.6','1.21.7','1.21.8',
        '1.21.9','1.21.10','1.21.11',
        '26.1','26.1.1','26.1.2','26.2','26.3'
    ),
    [switch]$Rebuild
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$legacy=@('1.18.2','1.19','1.19.1','1.19.2','1.19.3','1.19.4','1.20.1','1.20.2','1.20.3','1.20.4','1.20.5','1.20.6','1.21','1.21.1','1.21.2','1.21.3','1.21.4',
          '1.21.5','1.21.6','1.21.7','1.21.8','1.21.9','1.21.10','1.21.11')
$modern=@('26.1','26.1.1','26.1.2','26.2','26.3')
$approved=$legacy+$modern
$report=Join-Path $root 'release\OpenRad-1.18.2-to-26.3-build-status.json'
New-Item -ItemType Directory -Path (Join-Path $root 'release') -Force | Out-Null
$results = [System.Collections.Generic.List[object]]::new()
foreach($target in $Targets){
    if($target -notin $approved){throw "Target '$target' not in the released Java Minecraft series."}
}
foreach($target in $Targets){
    $isModern=$target -in $modern
    $modVersion='2.1.5'
    $buildDir=if($isModern){Join-Path $root 'modern'}else{$root}
    $jarName=if($target -in @("1.18.2", "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4", "1.20.1", "1.20.2", "1.20.3", "1.20.4")){"OpenRadClientMod-$modVersion-fabric-$target-java17-experimental.jar"}else{"OpenRadClientMod-$modVersion-fabric-$target.jar"}
    $jar=Join-Path $root "release\$jarName"
    $source=if($isModern){
      Join-Path $root "modern\build\libs\OpenRadClientMod-$modVersion-modern-$target.jar"
    }else{
      Join-Path $root "fabric\build\libs\fabric-$modVersion.jar"
    }
    $item=[ordered]@{minecraft=$target;java=if($isModern){25}elseif($target -in @("1.18.2","1.19","1.19.1","1.19.2","1.19.3","1.19.4","1.20.1","1.20.2","1.20.3","1.20.4")){17}else{21};
      modVersion=$modVersion;status='NOT_TESTED';experimental=($target -in @('1.18.2','1.19','1.19.1','1.19.2','1.19.3','1.19.4','1.20.1','1.20.2','1.20.3','1.20.4'));runtimeValidated=$false;jar=$null;error=$null;sha256=$null}
    try{
        if((Test-Path $jar) -and -not $Rebuild){
            & python (Join-Path $root 'tools\verify-version-jar.py') $jar $target $modVersion
            if($LASTEXITCODE -eq 0){$item.status='BUILD_VERIFIED';$item.jar=$jarName;
               $item.sha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
               Write-Output "[VERIFIED CACHE] $target";$results.Add([pscustomobject]$item);continue}
        }
        $args=@('--no-daemon',"-PopenradMinecraftVersion=$target")
        if($isModern){$args+=@(':build','--console=plain')}
        else{$args+=@(':core:test',':fabric:build',':fabric:test','--console=plain')}
        Push-Location $buildDir
        try{& ".\gradlew.bat" @args; $exit=$LASTEXITCODE}
        finally{Pop-Location}
        if($exit -ne 0){throw "Gradle failed with exit status $exit"}
        if(-not(Test-Path -LiteralPath $source)){throw 'Gradle succeeded but expected artifact was not created'}
        & python (Join-Path $root 'tools\verify-version-jar.py') $source $target $modVersion
        if($LASTEXITCODE -ne 0){throw 'Artifact metadata/bytecode verification failed'}
        Copy-Item -LiteralPath $source -Destination $jar -Force
        $item.status='BUILD_VERIFIED';$item.jar=$jarName
        $item.sha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Output "[BUILD VERIFIED] $target"
    }catch{
        $item.status='BUILD_FAILED'
        $item.error=$_.Exception.Message
        Write-Warning "Minecraft $target FAILED: $($item.error)"
    }
    $results.Add([pscustomobject]$item)
    [IO.File]::WriteAllText($report,($results|ConvertTo-Json -Depth 5),(New-Object Text.UTF8Encoding($false)))
}
[IO.File]::WriteAllText($report,($results|ConvertTo-Json -Depth 5),(New-Object Text.UTF8Encoding($false)))
Write-Output '================ RESULTS ================'
$results | Format-Table minecraft,status,java,jar -AutoSize
Write-Output ("STATUS_FILE="+$report)
if ($results | Where-Object { $_.status -ne 'BUILD_VERIFIED' }) { exit 1 }
