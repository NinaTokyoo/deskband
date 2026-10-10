# 收起/呼出方向验证：贴顶时必须向上滑出、从上方滑入；贴底则相反。
# 做法：轮询悬浮窗 frame（收起/呼出过程中窗口跟着锚点走，方向一眼可辨）。
$env:ANDROID_HOME = "D:\dsh\toolchain\sdk"
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$dumpFile = "$env:TEMP\pet_ui.xml"

function Get-Prefs {
    $raw = & $adb shell run-as com.dsh.deskpet cat /data/data/com.dsh.deskpet/shared_prefs/deskpet.xml 2>$null
    $m = @{}
    foreach ($l in $raw) { if ($l -match 'name="([^"]+)"\s+value="([^"]*)"') { $m[$Matches[1]] = $Matches[2] } }
    return $m
}

# 宠物悬浮窗的 frame；窗口不存在时返回 $null
function Get-PetFrame {
    $txt = (& $adb shell dumpsys window windows 2>$null) -join "`n"
    $m = [regex]::Match($txt, 'com\.dsh\.deskpet\}:\s*\r?\n(?:[^\n]*\r?\n)*?\s*mAttrs=\{\((-?\d+),(-?\d+)\)\((\d+)x(\d+)\)')
    if (-not $m.Success) { return $null }
    return [pscustomobject]@{
        x = [int]$m.Groups[1].Value
        y = [int]$m.Groups[2].Value
        w = [int]$m.Groups[3].Value
        h = [int]$m.Groups[4].Value
    }
}

# 按文字点按钮（重新 dump，避免布局变了还按旧坐标）
function Tap-ButtonByText($text) {
    & $adb shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
    & $adb pull /sdcard/ui.xml $dumpFile 2>$null | Out-Null
    # ⚠️ 必须用 .NET 按 UTF-8 读：Get-Content 在 GBK 代码页下会把中文读成乱码，
    # 按文字找按钮就会永远找不到。
    $c = [System.IO.File]::ReadAllText($dumpFile, [System.Text.Encoding]::UTF8)
    $m = [regex]::Match($c, 'text="' + [regex]::Escape($text) + '[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { Write-Output "!! 找不到按钮：$text"; return $false }
    $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    Write-Output ("tap '{0}' at ({1},{2})" -f $text, $cx, $cy)
    & $adb shell input tap $cx $cy | Out-Null
    return $true
}

function Show-Frame($tag) {
    $f = Get-PetFrame
    if ($null -eq $f) { Write-Output ("{0,-14} 窗口不存在" -f $tag) }
    else { Write-Output ("{0,-14} window x={1,-6} y={2,-6} {3}x{4}" -f $tag, $f.x, $f.y, $f.w, $f.h) }
    return $f
}

# 点按钮后连续采样窗口位置（每帧一次 dumpsys，约 200-400ms）
function Sample-After($text, $n) {
    Tap-ButtonByText $text | Out-Null
    for ($i = 1; $i -le $n; $i++) { Show-Frame ("  t{0}" -f $i) | Out-Null }
}
