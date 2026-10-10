# 桌宠验证小工具：拖动 / 读状态 / 截图
# 环境变量每次都要显式设（DSH 子进程环境是冻结的）
$env:JAVA_HOME = "D:\dsh\toolchain\jdk\jdk-21.0.12.1+1"
$env:ANDROID_HOME = "D:\dsh\toolchain\sdk"
$script:adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$script:shotDir = "D:\dsh\dsh work\pet-app\docs\shots"
New-Item -ItemType Directory -Force -Path $script:shotDir | Out-Null

function Get-Prefs {
    $raw = & $script:adb shell run-as com.dsh.deskpet cat /data/data/com.dsh.deskpet/shared_prefs/deskpet.xml 2>&1
    $m = @{}
    foreach ($line in $raw) {
        if ($line -match 'name="([^"]+)"\s+value="([^"]*)"') { $m[$Matches[1]] = $Matches[2] }
    }
    return $m
}

function Show-Prefs($tag) {
    $p = Get-Prefs
    $state = $p['snap_state']
    $names = @{ '0' = 'CORNER_BR'; '1' = 'CORNER_BL'; '2' = 'BORDER_R'; '3' = 'BORDER_L'
                '5' = 'BORDER_R_UPRIGHT'; '6' = 'BORDER_L_UPRIGHT'
                '7' = 'CORNER_TR'; '8' = 'CORNER_TL'; '9' = 'EDGE_TOP_R'; '10' = 'EDGE_TOP_L' }
    $sn = if ($names.ContainsKey($state)) { $names[$state] } else { "?$state" }
    Write-Output ("[{0}] snap_state={1} ({2})  pos_left={3}  pos_top={4}" -f $tag, $state, $sn, $p['pos_left'], $p['pos_top'])
    return $p
}

# 用 input motionevent 构造触摸序列（swipe 的结果无法判读）
function Invoke-Drag([int]$x1, [int]$y1, [int]$x2, [int]$y2, [int]$steps = 8) {
    & $script:adb shell input motionevent DOWN $x1 $y1 | Out-Null
    for ($i = 1; $i -le $steps; $i++) {
        $x = [int]($x1 + ($x2 - $x1) * $i / $steps)
        $y = [int]($y1 + ($y2 - $y1) * $i / $steps)
        & $script:adb shell input motionevent MOVE $x $y | Out-Null
    }
    Start-Sleep -Milliseconds 120
    & $script:adb shell input motionevent UP $x2 $y2 | Out-Null
    Start-Sleep -Milliseconds 900   # 等吸附补间 + 回弹走完 + onSettled 落盘(400ms)
}

function Save-Shot([string]$name) {
    # ⚠️ 不能用 `adb exec-out screencap -p > file`：PowerShell 的重定向会把二进制
    # 当文本转码（存成 UTF-16），PNG 直接损坏。走"设备内落盘 + adb pull"，
    # 且 stderr 要用 2>$null 丢掉（adb pull 会把进度写到 stderr）。
    $path = Join-Path $script:shotDir ($name + ".png")
    & $script:adb shell screencap -p /sdcard/_shot.png 2>$null | Out-Null
    & $script:adb pull /sdcard/_shot.png $path 2>$null | Out-Null
    Write-Output $path
}

# 宠物所在的悬浮窗 frame（验证收起/呼出方向用）
function Get-PetWindow {
    $raw = & $script:adb shell dumpsys window windows 2>&1
    $txt = $raw -join "`n"
    $i = $txt.IndexOf('deskpet')
    if ($i -lt 0) { return $null }
    $seg = $txt.Substring($i, [Math]::Min(1600, $txt.Length - $i))
    $m = [regex]::Match($seg, 'mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
    if (-not $m.Success) { $m = [regex]::Match($seg, 'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') }
    if (-not $m.Success) { return $null }
    return [pscustomobject]@{
        left = [int]$m.Groups[1].Value; top = [int]$m.Groups[2].Value
        right = [int]$m.Groups[3].Value; bottom = [int]$m.Groups[4].Value
    }
}
