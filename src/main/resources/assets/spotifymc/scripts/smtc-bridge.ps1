# Music MC - Windows media session bridge
#
# Reads "now playing" info from Windows' System Media Transport Controls (the same data the
# volume-key media overlay shows) and sends play/pause/next/previous back to the player.
# Works with Spotify, Apple Music, iTunes, browsers, etc. No accounts or API keys involved.
#
# Protocol (one line in, one JSON line out, over stdin/stdout):
#   state            -> {"ok":true,"sessions":[...]}
#   toggle <appId>   -> {"ok":true|false}
#   next <appId>     -> {"ok":true|false}
#   prev <appId>     -> {"ok":true|false}
#   quit             -> exits
#
# Must run under Windows PowerShell 5.1 (powershell.exe); PowerShell 7 can't load WinRT types.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch {}
try { [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false) } catch {}

function Write-Line($obj) {
    [Console]::Out.WriteLine((ConvertTo-Json -InputObject $obj -Compress -Depth 5))
    [Console]::Out.Flush()
}

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
    $null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
} catch {
    Write-Line @{ ready = $false; error = "Windows media controls are not available: $($_.Exception.Message)" }
    exit 1
}

$script:asTaskGeneric = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

function Await($op, [Type]$resultType) {
    $task = $script:asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($op))
    if (-not $task.Wait(4000)) { throw 'Timed out waiting for Windows media API' }
    return $task.Result
}

try {
    $script:mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
} catch {
    Write-Line @{ ready = $false; error = "Could not open Windows media session manager: $($_.Exception.Message)" }
    exit 1
}

# Album art is written to %TEMP%\spotifymc-art and referenced by path.
$script:artDir = Join-Path $env:TEMP 'spotifymc-art'
try {
    New-Item -ItemType Directory -Force -Path $script:artDir | Out-Null
    Get-ChildItem -Path $script:artDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-1) } |
        Remove-Item -Force -ErrorAction SilentlyContinue
} catch {}

$script:artCache = @{}      # track key -> file path ('' = gave up)
$script:artAttempts = @{}   # track key -> attempts so far
$script:md5 = [System.Security.Cryptography.MD5]::Create()

function Get-Hash([string]$text) {
    $bytes = $script:md5.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($text))
    return ([System.BitConverter]::ToString($bytes)).Replace('-', '').Substring(0, 16).ToLowerInvariant()
}

function Save-Thumbnail($props, [string]$key) {
    if ($script:artCache.ContainsKey($key)) { return $script:artCache[$key] }

    $attempts = 0
    if ($script:artAttempts.ContainsKey($key)) { $attempts = $script:artAttempts[$key] }
    if ($attempts -ge 5) { $script:artCache[$key] = ''; return '' }
    $script:artAttempts[$key] = $attempts + 1

    # Players often publish the thumbnail a moment after the title, so retry on later polls.
    if ($null -eq $props.Thumbnail) { return '' }

    $path = Join-Path $script:artDir ((Get-Hash $key) + '.img')
    $stream = $null
    try {
        $stream = Await ($props.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
        $size = [uint32]$stream.Size
        if ($size -eq 0) { return '' }
        $reader = [Windows.Storage.Streams.DataReader]::new($stream.GetInputStreamAt(0))
        $null = Await ($reader.LoadAsync($size)) ([uint32])
        $bytes = New-Object byte[] $size
        $reader.ReadBytes($bytes)
        [System.IO.File]::WriteAllBytes($path, $bytes)
        $script:artCache[$key] = $path
        return $path
    } catch {
        return ''
    } finally {
        if ($null -ne $stream) { try { $stream.Dispose() } catch {} }
    }
}

function Get-State {
    $current = $script:mgr.GetCurrentSession()
    $currentId = ''
    if ($null -ne $current) { $currentId = [string]$current.SourceAppUserModelId }

    $list = New-Object System.Collections.ArrayList
    foreach ($s in $script:mgr.GetSessions()) {
        try {
            $appId = [string]$s.SourceAppUserModelId
            $props = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
            $playback = $s.GetPlaybackInfo()
            $timeline = $s.GetTimelineProperties()
            $status = [string]$playback.PlaybackStatus

            $durationMs = [double]($timeline.EndTime - $timeline.StartTime).TotalMilliseconds
            $positionMs = [double]($timeline.Position - $timeline.StartTime).TotalMilliseconds
            $positionKnown = $durationMs -gt 0
            if ($positionKnown -and $status -eq 'Playing' -and $timeline.LastUpdatedTime.Year -gt 2000) {
                # Position is a snapshot taken at LastUpdatedTime; bring it up to now.
                $positionMs += ([DateTimeOffset]::Now - $timeline.LastUpdatedTime).TotalMilliseconds
            }
            if ($positionMs -lt 0) { $positionMs = 0 }
            if ($positionKnown -and $positionMs -gt $durationMs) { $positionMs = $durationMs }

            $title = [string]$props.Title
            $artist = [string]$props.Artist
            $album = [string]$props.AlbumTitle
            $art = ''
            if ($title -ne '') {
                $art = Save-Thumbnail $props ($appId + '|' + $title + '|' + $artist + '|' + $album)
            }

            [void]$list.Add(@{
                app           = $appId
                current       = ($appId -eq $currentId)
                status        = $status
                title         = $title
                artist        = $artist
                album         = $album
                albumArtist   = [string]$props.AlbumArtist
                durationMs    = [long]$durationMs
                positionMs    = [long]$positionMs
                positionKnown = $positionKnown
                art           = $art
            })
        } catch {
            # Skip sessions that disappear mid-read.
        }
    }
    return @{ ok = $true; sessions = $list.ToArray() }
}

function Find-Session([string]$appId) {
    foreach ($s in $script:mgr.GetSessions()) {
        if ([string]$s.SourceAppUserModelId -eq $appId) { return $s }
    }
    return $script:mgr.GetCurrentSession()
}

Write-Line @{ ready = $true }

while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { exit 0 }
    $line = $line.Trim()
    if ($line -eq '') { continue }

    $cmd = $line
    $arg = ''
    $space = $line.IndexOf(' ')
    if ($space -gt 0) {
        $cmd = $line.Substring(0, $space)
        $arg = $line.Substring($space + 1)
    }

    $out = $null
    try {
        if ($cmd -eq 'quit') { exit 0 }
        elseif ($cmd -eq 'state') { $out = Get-State }
        elseif ($cmd -eq 'toggle' -or $cmd -eq 'next' -or $cmd -eq 'prev') {
            $session = Find-Session $arg
            if ($null -eq $session) {
                $out = @{ ok = $false; error = 'No media session found' }
            } else {
                if ($cmd -eq 'toggle') { $op = $session.TryTogglePlayPauseAsync() }
                elseif ($cmd -eq 'next') { $op = $session.TrySkipNextAsync() }
                else { $op = $session.TrySkipPreviousAsync() }
                $out = @{ ok = [bool](Await $op ([bool])) }
            }
        }
        else { $out = @{ ok = $false; error = "Unknown command: $cmd" } }
    } catch {
        $out = @{ ok = $false; error = $_.Exception.Message }
    }
    Write-Line $out
}
