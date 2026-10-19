# Real in-process regression for delayed Radmin diagnostic pipe writes.
# This creates only a local, randomly named pipe; it never starts a process,
# registers a scheduled task, stops a service or changes any adapter.
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../../src/platform/windows_radmin.ps1'
. $source
$server = $null
$client = $null
$name = 'OpenRad-PipeDelayedTest-' + [Guid]::NewGuid().ToString('N')
try {
    $server = [IO.Pipes.NamedPipeServerStream]::new($name, [IO.Pipes.PipeDirection]::In, 1, [IO.Pipes.PipeTransmissionMode]::Byte, [IO.Pipes.PipeOptions]::Asynchronous)
    $connection = $server.BeginWaitForConnection($null, $null)
    $client = [IO.Pipes.NamedPipeClientStream]::new('.', $name, [IO.Pipes.PipeDirection]::Out)
    $client.Connect(2000)
    $state = @{}
    $initial = Read-OpenRadRadminResult -Pipe $server -Connection $connection -ReadState $state
    if ($initial -ne '') { throw 'Read reported data before the worker wrote anything.' }
    if ($null -eq $state.Task) { throw 'Initial read did not remain pending.' }
    $pending = $state.Task
    Start-Sleep -Milliseconds 1250
    $stillWaiting = Read-OpenRadRadminResult -Pipe $server -Connection $connection -ReadState $state
    if ($stillWaiting -ne '' -or -not [object]::ReferenceEquals($pending, $state.Task)) {
        throw 'Read was replaced or completed before the delayed write.'
    }
    $expected = 'synthetic delayed Radmin error'
    $bytes = [Text.Encoding]::UTF8.GetBytes($expected)
    $client.Write($bytes, 0, $bytes.Length)
    $result = ''
    $deadline = [DateTime]::UtcNow.AddSeconds(3)
    do {
        $result = Read-OpenRadRadminResult -Pipe $server -Connection $connection -ReadState $state
        if ($result -ne '') { break }
        Start-Sleep -Milliseconds 20
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($result -cne $expected) { throw ('Lost delayed Radmin diagnostic; got: ' + $result) }
    Write-Output 'PASS: delayed real named-pipe diagnostic retained (>1 second), no system modifications.'
} finally {
    if ($null -ne $client) { $client.Dispose() }
    if ($null -ne $server) { $server.Dispose() }
}
