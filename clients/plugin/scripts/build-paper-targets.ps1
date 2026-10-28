param([string[]]$Targets = @('1.20.6','1.21','26.1.1','26.1.2','26.2','26.3'))
$ErrorActionPreference='Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$dest = Join-Path $root 'release'
New-Item -ItemType Directory -Force $dest | Out-Null
$known=@{'1.20.6'='1.20.6-R0.1-SNAPSHOT';'1.21'='1.20.6-R0.1-SNAPSHOT';'26.1.1'='26.1.1.build.+';'26.1.2'='26.1.2.build.+';'26.2'='26.2.build.+';'26.3'='26.3.build.+'}
$results=@()
foreach($target in $Targets){
  if(-not $known.ContainsKey($target)){throw "Unsupported Paper target: $target"}
  $java=if($target -in @('1.20.6','1.21')){21}else{25}
  $name="OpenRadPlugin-2.1.5-paper-$target-java$java.jar"
  $result=[ordered]@{minecraft=$target;paperApi=$known[$target];java=$java;status='BUILD_FAILED';jar=$null;sha256=$null}
  Push-Location $root
  try {
    & '.\gradlew.bat' --no-daemon --max-workers=1 "-PopenradPaperApi=$($known[$target])" ':check' '--console=plain'
    if($LASTEXITCODE -ne 0){throw "Gradle check failed for Paper $target"}
    $jar=Join-Path $root 'build\libs\OpenRadPlugin.jar'
    if(-not(Test-Path $jar)){throw "Missing verified Paper jar for $target"}
    Copy-Item -LiteralPath $jar -Destination (Join-Path $dest $name) -Force
    $result.status='BUILD_VERIFIED';$result.jar=$name
    $result.sha256=(Get-FileHash (Join-Path $dest $name) -Algorithm SHA256).Hash.ToLowerInvariant()
  } finally {Pop-Location}
  $results+=[pscustomobject]$result
  Write-Output "[PAPER $target] $($result.status) $name"
}
$results | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $dest 'paper-2.1.5-build-status.json')
$results | ForEach-Object { "$($_.sha256)  $($_.jar)" } | Set-Content -Encoding ascii (Join-Path $dest 'SHA256SUMS-paper.txt')
