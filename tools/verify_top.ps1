# 上边吸附实机验证：逐个拖到目标位置，读回 snap_state / pos_left / pos_top 并截图。
# 环境变量每次都要显式设（DSH 子进程环境是冻结的）。
$env:ANDROID_HOME = "D:\dsh\toolchain\sdk"
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$shotDir = "D:\dsh\dsh work\pet-app\docs\shots"
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

$STATE_NAMES = @{
    0 = 'CORNER_BR'; 1 = 'CORNER_BL'; 2 = 'BORDER_R'; 3 = 'BORDER_L'
    5 = 'BORDER_R_UPRIGHT'; 6 = 'BORDER_L_UPRIGHT'
    7 = 'CORNER_TR'; 8 = 'CORNER_TL'; 9 = 'EDGE_TOP_R'; 10 = 'EDGE_TOP_L'
}
# 三套素材缩放后的足迹宽（cellH = 屏幕高 3084 × 8% ≈ 247）
$CELL_H = 247; $W_UPRIGHT = 289; $W_CORNER = 263

function Get-Prefs {
    $raw = & $adb shell run-as com.dsh.deskpet cat /data/data/com.dsh.deskpet/shared_prefs/deskpet.xml 2>$null
    $m = @{}
    foreach ($l in $raw) { if ($l -match 'name="([^"]+)"\s+value="([^"]*)"') { $m[$Matches[1]] = $Matches[2] } }
    return $m
}

function Show-State($tag) {
    $p = Get-Prefs
    $s = [int]$p['snap_state']
    $n = if ($STATE_NAMES.ContainsKey($s)) { $STATE_NAMES[$s] } else { "?$s" }
    Write-Output ("{0,-16} snap_state={1,-2} {2,-18} pos_left={3,-5} pos_top={4}" -f $tag, $s, $n, $p['pos_left'], $p['pos_top'])
}

function Invoke-Drag($x1, $y1, $x2, $y2) {
    & $adb shell input motionevent DOWN $x1 $y1 | Out-Null
    for ($i = 1; $i -le 8; $i++) {
        $x = [int]($x1 + ($x2 - $x1) * $i / 8)
        $y = [int]($y1 + ($y2 - $y1) * $i / 8)
        & $adb shell input motionevent MOVE $x $y | Out-Null
    }
    Start-Sleep -Milliseconds 150
    & $adb shell input motionevent UP $x2 $y2 | Out-Null
    Start-Sleep -Milliseconds 1000   # 等吸附补间 + 回弹 + onSettled 落盘(400ms)
}

# 宠物悬浮窗的 frame（真实位置；prefs 里的坐标可能被 restore 夹取过，未必是当前值）
function Get-PetFrame {
    $txt = (& $adb shell dumpsys window windows 2>$null) -join "`n"
    $m = [regex]::Match($txt, 'com\.dsh\.deskpet\}:\s*\r?\n(?:[^\n]*\r?\n)*?\s*mAttrs=\{\((-?\d+),(-?\d+)\)\((\d+)x(\d+)\)')
    if (-not $m.Success) { return $null }
    return [pscustomobject]@{
        x = [int]$m.Groups[1].Value; y = [int]$m.Groups[2].Value
        w = [int]$m.Groups[3].Value; h = [int]$m.Groups[4].Value
    }
}

# 宠物足迹（= 素材格子）左上角，由**窗口 frame 反算**：
#   足迹 = 窗口 + (HOP_PAD_X + footOffX, topPad + footOffY)
function Get-Footprint {
    $f = Get-PetFrame
    if ($null -eq $f) { return $null }
    $p = Get-Prefs
    $s = [int]$p['snap_state']
    $rotated = ($s -eq 2) -or ($s -eq 3)
    $corner = ($s -eq 0) -or ($s -eq 1) -or ($s -eq 7) -or ($s -eq 8)
    $tw = $W_UPRIGHT
    if ($rotated) { $tw = $CELL_H }
    if ($corner) { $tw = $W_CORNER }
    $th = $CELL_H
    if ($rotated) { $th = $W_UPRIGHT }
    $pad = [int]($th * 0.08) + 8
    $footOffX = [int](($f.w - 16 - $tw) / 2)
    $footOffY = [int](($f.h - 2 * $pad - $th) / 2)
    return [pscustomobject]@{
        left = $f.x + 8 + $footOffX
        top = $f.y + $pad + $footOffY
        tw = $tw
        th = $th
        state = $s
    }
}

# 起手点：足迹中心；贴上沿时往下挪开状态栏（否则触摸落到状态栏变成下拉通知栏）
function Get-Center {
    $fp = Get-Footprint
    if ($null -eq $fp) { return $null }
    $cx = $fp.left + [int]($fp.tw / 2)
    $cy = $fp.top + [int]($fp.th / 2)
    if ($cy -lt 200) { $cy = $fp.top + $fp.th - 20 }
    return $cx, $cy
}

function Save-Shot($name) {
    & $adb shell screencap -p /sdcard/_shot.png 2>$null
    & $adb pull /sdcard/_shot.png (Join-Path $shotDir ($name + ".png")) 2>$null | Out-Null
}

function Move-To($tx, $ty) {
    $c = Get-Center
    Invoke-Drag $c[0] $c[1] $tx $ty
}
