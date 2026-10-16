# ============================================================================
#  DeskBand -> Gitee 一键推送
#
#  用法：
#    1. 去 https://gitee.com/profile/personal_access_tokens 生成令牌
#       （描述随意，权限勾 projects，有效期建议 30 天）
#    2. 在本目录执行：
#         powershell -ExecutionPolicy Bypass -File .\push-gitee.ps1
#       （系统默认禁止运行 .ps1，用 -ExecutionPolicy Bypass 单次绕过即可；
#         不要用 Set-ExecutionPolicy Unrestricted 改全局设置）
#    3. 提示时按 Ctrl+V 粘贴令牌、回车
#
#  ⚠️ 为什么用 GIT_ASKPASS 而不是把令牌拼进 URL：
#     拼 URL（https://user:token@host/...）时，令牌里只要含 @ : / ? #
#     就会把 URL 解析坏，报 "URL rejected: Malformed input to a URL function"
#     或 "Port number was not a decimal number" —— 实测踩过，第一次推送就栽在这。
#     走 GIT_ASKPASS 时令牌只通过环境变量传给 git，不经过 URL 解析，
#     特殊字符一律安全，也不会出现在命令行与日志里。
# ============================================================================

#  ⚠️ 仓库名是 deskband01（不是 deskband）：最初建的空仓库叫 deskband，
#     后来用 Gitee 的「导入 GitHub 仓库」时它自动建成了 deskband01，
#     用户 2026-10-17 决定就用这个名字。App 里的检查更新两个名字都会试。

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$RepoUrl = 'https://gitee.com/ninatokyoo/deskband01.git'
$User    = 'ninatokyoo'
$WebUrl  = 'https://gitee.com/ninatokyoo/deskband01'
$AskPass = Join-Path $PSScriptRoot 'tools\git-askpass.ps1'

function Line { param($c = '-') Write-Host ($c * 62) }

Line '='
Write-Host ' DeskBand -> Gitee 推送'
Line '='

# ---- 自检：把问题暴露在粘贴令牌之前 ----
if (-not (Test-Path '.git')) { Write-Host 'x 当前目录不是 git 仓库，请先 cd 到项目目录'; exit 1 }
if (-not (Test-Path $AskPass)) { Write-Host "x 找不到凭据辅助脚本: $AskPass"; exit 1 }

$branch = (git rev-parse --abbrev-ref HEAD).Trim()
$local  = (git rev-parse HEAD).Trim()
$dirty  = (git status --porcelain)

Write-Host "  目标仓库  : $WebUrl"
Write-Host "  分支      : $branch"
Write-Host "  本地提交  : $($local.Substring(0,12))"
if ($dirty) { Write-Host '  工作区    : 有未提交改动（不影响推送已提交内容）' }
else        { Write-Host '  工作区    : 干净' }
if ($branch -ne 'main') { Write-Host "  注意：当前不在 main 分支，推送的会是 $branch" }

foreach ($pair in @(@('app/src/main/assets/pet','角色素材'), @('docs','开发截图'),
                    @('keystore','签名密钥库'), @('local.properties','本机配置'))) {
  if ((git ls-files $pair[0])) { Write-Host "  注意：$($pair[1]) 竟然被跟踪了，建议先排除再推" }
}

Line
$sec = Read-Host '粘贴 Gitee 令牌（不会显示），然后回车' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
try   { $token = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr).Trim() }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

if (-not $token) { Write-Host 'x 没有输入令牌，已取消'; exit 1 }
# 从网页复制令牌时常夹带换行/空格，先去掉
$token = $token -replace '\s', ''

Line
Write-Host '正在推送……（首次要传 167 个提交，可能要一两分钟）'

# 令牌只走环境变量，不进 URL、不进命令行
$env:GITEE_TOKEN = $token
$env:GITEE_USER  = $User
$env:GIT_ASKPASS = $AskPass
$env:GIT_TERMINAL_PROMPT = '0'      # 关掉交互提示，避免卡住等输入

$raw  = (& git push $RepoUrl "${branch}:main" 2>&1 | Out-String)
$code = $LASTEXITCODE

# 保险：万一 git 把令牌回显出来，显示前先抹掉
$safe = $raw.Replace($token, '***')
($safe.Trim() -split "`n" | Select-Object -Last 14) | ForEach-Object { Write-Host "  $_" }

# 收尾：把令牌从环境里清掉
Remove-Item Env:GITEE_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:GIT_ASKPASS -ErrorAction SilentlyContinue

if ($code -ne 0) {
  Line
  Write-Host "x 推送失败（退出码 $code）。常见原因："
  Write-Host '   - 令牌权限没勾 projects      -> 重新生成一个'
  Write-Host '   - 令牌已过期或被撤销         -> 重新生成'
  Write-Host '   - 用户名不是 ninatokyoo      -> 核对 Gitee 地址栏里的用户名'
  Write-Host '   - 账号还没完成 2FA           -> 先在 Gitee 安全设置里开启'
  exit $code
}

Line
Write-Host 'v 推送成功，正在核对远程……'

$remote = (& git ls-remote $RepoUrl refs/heads/main 2>&1 | Out-String).Trim()
if ($remote) {
  $rh = ($remote -split '\s+')[0]
  Write-Host "  远程 main : $($rh.Substring(0,12))"
  Write-Host "  本地 HEAD : $($local.Substring(0,12))"
  if ($rh -eq $local) { Write-Host 'v 两边一致，推送完整' }
  else                { Write-Host '! 两边不一致 —— 可能有别人同时推过，或推送被截断' }
} else {
  Write-Host '! 读不到远程 main（若是私有仓库则正常；稍等刷新页面看看）'
}

Line
Write-Host "完成。打开看看： $WebUrl"
