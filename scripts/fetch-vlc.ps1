# Downloads the portable VLC build that 1-stream bundles and trims what libVLC embedding does not need.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\fetch-vlc.ps1
$ErrorActionPreference = 'Stop'
$version = '3.0.21'
$root = Split-Path -Parent $PSScriptRoot
$dst = Join-Path $root 'desktopApp\appResources\windows-x64\vlc'
$tmp = Join-Path $env:TEMP "vlc-$version-win64.zip"
$work = Join-Path $env:TEMP "vlc-$version-extract"
function Drop($p) {
    if (Test-Path -LiteralPath $p -PathType Container) { [IO.Directory]::Delete($p, $true) }
    elseif (Test-Path -LiteralPath $p) { [IO.File]::Delete($p) }
}
Invoke-WebRequest "https://get.videolan.org/vlc/$version/win64/vlc-$version-win64.zip" -OutFile $tmp
Drop $work
Expand-Archive $tmp $work
New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
Drop $dst
Copy-Item -Path "$work\vlc-$version" -Destination $dst -Recurse
foreach ($n in 'vlc.exe','vlc-cache-gen.exe','uninstall.exe','locale','skins','lua','hrtfs','msi','sdk','plugins\gui','plugins\lua') {
    Drop (Join-Path $dst $n)
}
Write-Host "VLC $version ready in $dst"