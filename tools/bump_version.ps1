# 版本号自动顺延：读 app/build.gradle.kts 里的 vName，末位 +1 写回。
#
#   pwsh tools/bump_version.ps1           # 1.1.02 -> 1.1.03
#   pwsh tools/bump_version.ps1 -Show     # 只看当前版本，不改
#
# versionCode 不用管：它在 build.gradle.kts 里由 versionName 自动派生。
param([switch]$Show)

$ErrorActionPreference = 'Stop'
$gradle = Join-Path $PSScriptRoot '..\app\build.gradle.kts'
$text = [System.IO.File]::ReadAllText($gradle)
$m = [regex]::Match($text, 'val\s+vName\s*=\s*"(\d+)\.(\d+)\.(\d+)"')
if (-not $m.Success) { throw "在 $gradle 里找不到 vName = `"x.y.z`"" }

$major = [int]$m.Groups[1].Value
$minor = [int]$m.Groups[2].Value
$patch = [int]$m.Groups[3].Value
$old = "$major.$minor.$patch"

if ($Show) { Write-Output $old; exit 0 }

# 只顺延末位；末位满 100 时进位到次位（1.1.99 -> 1.2.00）
$new = if ($patch + 1 -ge 100) { "$major.$($minor + 1).00" } else { "$major.$minor.$($patch + 1)" }

$text = $text.Remove($m.Index, $m.Length).Insert($m.Index, "val vName = `"$new`"")
[System.IO.File]::WriteAllText($gradle, $text)
Write-Output "$old -> $new"
