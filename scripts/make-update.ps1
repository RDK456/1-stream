<#
  Builds the update feed for a release: update.json, the full MSI/Setup copies, and one delta patch per old version.

  Example:
    ./scripts/make-update.ps1 -Version 1.0.1 `
      -NewDir  desktopApp\build\compose\binaries\main\app\1-stream `
      -Old     '1.0.0=E:\releases\1.0.0\app-image' `
      -Msi     desktopApp\build\compose\binaries\main\msi\1-stream-1.0.1.msi `
      -Setup   desktopApp\build\compose\binaries\main\exe\1-stream-1.0.1.exe `
      -Out     E:\releases\1.0.1\feed

  Upload everything in -Out to the same place as update.json (e.g. a GitHub release / Pages) and point the build's
  `update.url` gradle property at update.json. Clients on 1.0.0 download only patch-1.0.0-to-1.0.1.zip.
#>
param(
  [Parameter(Mandatory)] [string] $Version,
  [Parameter(Mandatory)] [string] $NewDir,
  [string[]] $Old = @(),
  [Parameter(Mandatory)] [string] $Msi,
  [string] $Setup,
  [Parameter(Mandatory)] [string] $Out,
  [string] $Notes = ""
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Get-Manifest([string]$dir) {
  $root = (Resolve-Path $dir).Path.TrimEnd('\')
  $m = [ordered]@{}
  Get-ChildItem $root -Recurse -File | Sort-Object FullName | ForEach-Object {
    $rel = $_.FullName.Substring($root.Length + 1).Replace('\', '/')
    $m[$rel] = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
  }
  $m
}
function Copy-Asset([string]$src) {
  $dest = Join-Path $Out (Split-Path $src -Leaf)
  Copy-Item $src $dest -Force
  [ordered]@{ url = (Split-Path $src -Leaf); sha256 = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower(); size = (Get-Item $dest).Length }
}

New-Item -ItemType Directory -Force $Out | Out-Null
$newRoot = (Resolve-Path $NewDir).Path.TrimEnd('\')
$new = Get-Manifest $NewDir
$patches = @()
foreach ($o in $Old) {
  $from, $path = $o.Split('=', 2)
  $oldM = Get-Manifest $path
  $changed = @($new.Keys | Where-Object { -not $oldM.Contains($_) -or $oldM[$_] -ne $new[$_] })
  $removed = @($oldM.Keys | Where-Object { -not $new.Contains($_) })
  $zipPath = Join-Path $Out "patch-$from-to-$Version.zip"
  if (Test-Path $zipPath) { [IO.File]::Delete($zipPath) }
  $zip = [IO.Compression.ZipFile]::Open($zipPath, 'Create')
  try {
    foreach ($rel in $changed) {
      [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $newRoot $rel.Replace('/', '\')), $rel, 'Optimal')
    }
  } finally { $zip.Dispose() }
  $patches += [ordered]@{
    from = $from; url = (Split-Path $zipPath -Leaf)
    sha256 = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLower(); size = (Get-Item $zipPath).Length
    removed = $removed
  }
  "{0}: {1} changed, {2} removed, patch {3:N1} MB" -f $from, $changed.Count, $removed.Count, ((Get-Item $zipPath).Length / 1MB)
}

$feed = [ordered]@{ version = $Version; notes = $Notes; msi = (Copy-Asset $Msi); files = $new; patches = $patches }
if ($Setup) { [void](Copy-Asset $Setup) }
[IO.File]::WriteAllText((Join-Path $Out 'update.json'), ($feed | ConvertTo-Json -Depth 6), $utf8)
"Wrote $Out\update.json"
