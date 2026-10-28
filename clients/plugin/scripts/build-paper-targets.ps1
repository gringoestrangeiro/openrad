param([string[]]$Targets = @('1.17.1','1.18.1','1.18.2','1.19','1.19.1','1.19.2','1.19.3','1.19.4','1.20.1','1.20.2','1.20.3','1.20.4','1.20.5','1.20.6','1.21','26.1.1','26.1.2','26.2','26.3'))
$ErrorActionPreference='Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$dest = Join-Path $root 'release'
New-Item -ItemType Directory -Force $dest | Out-Null
$known=@{'1.17.1'='1.17.1-R0.1-SNAPSHOT';'1.18.1'='1.18.1-R0.1-SNAPSHOT';'1.18.2'='1.18.2-R0.1-SNAPSHOT';'1.19'='1.19-R0.1-SNAPSHOT';'1.19.1'='1.19.1-R0.1-SNAPSHOT';'1.19.2'='1.19.2-R0.1-SNAPSHOT';'1.19.3'='1.19.3-R0.1-SNAPSHOT';'1.19.4'='1.19.4-R0.1-SNAPSHOT';'1.20.1'='1.20.1-R0.1-SNAPSHOT';'1.20.2'='1.20.2-R0.1-SNAPSHOT';'1.20.3'='1.20.3-R0.1-SNAPSHOT';'1.20.4'='1.20.4-R0.1-SNAPSHOT';'1.20.5'='1.20.5-R0.1-SNAPSHOT';'1.20.6'='1.20.6-R0.1-SNAPSHOT';'1.21'='1.20.6-R0.1-SNAPSHOT';'26.1.1'='26.1.1.build.+';'26.1.2'='26.1.2.build.+';'26.2'='26.2.build.+';'26.3'='26.3.build.+'}
$results=@()
foreach($target in $Targets){
  if(-not $known.ContainsKey($target)){throw "Unsupported Paper target: $target"}
  $java=if($target -eq '1.17.1'){16}elseif($target -in @('1.18.1','1.18.2','1.19','1.19.1','1.19.2','1.19.3','1.19.4','1.20.1','1.20.2','1.20.3','1.20.4')){17}elseif($target -in @('1.20.5','1.20.6','1.21')){21}else{25}
  $name=if($target -in @("1.17.1", "1.18.1", "1.18.2", "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4", "1.20.1", "1.20.2", "1.20.3", "1.20.4")){"OpenRadPlugin-2.1.5-paper-$target-java$java-experimental.jar"}else{"OpenRadPlugin-2.1.5-paper-$target-java$java.jar"}
  $result=[ordered]@{minecraft=$target;paperApi=$known[$target];java=$java;status='BUILD_FAILED';experimental=($target -in @('1.17.1','1.18.1','1.18.2','1.19','1.19.1','1.19.2','1.19.3','1.19.4','1.20.1','1.20.2','1.20.3','1.20.4','1.20.5'));runtimeValidated=$false;jar=$null;sha256=$null;error=$null}
  Push-Location $root
  try {
    & '.\gradlew.bat' --no-daemon --max-workers=1 "-PopenradPaperApi=$($known[$target])" ':check' '--console=plain'
    if($LASTEXITCODE -ne 0){throw "Gradle check failed for Paper $target"}
    $jar=Join-Path $root 'build\libs\OpenRadPlugin.jar'
    if(-not(Test-Path $jar)){throw "Missing verified Paper jar for $target"}
    Copy-Item -LiteralPath $jar -Destination (Join-Path $dest $name) -Force
    $result.status='BUILD_VERIFIED';$result.jar=$name
    $result.sha256=(Get-FileHash (Join-Path $dest $name) -Algorithm SHA256).Hash.ToLowerInvariant()
  } catch {
    $result.status='BUILD_FAILED';$result.error=$_.Exception.Message
    Write-Warning "[PAPER $target] $($result.error)"
  } finally {Pop-Location}
  $results+=[pscustomobject]$result
  Write-Output "[PAPER $target] $($result.status) $name"
}
$results | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $dest 'paper-2.1.5-build-status.json')
$results | Where-Object { $_.status -eq 'BUILD_VERIFIED' } | ForEach-Object { "$($_.sha256)  $($_.jar)" } | Set-Content -Encoding ascii (Join-Path $dest 'SHA256SUMS-paper.txt')
if ($results | Where-Object { $_.status -ne 'BUILD_VERIFIED' }) { exit 1 }
