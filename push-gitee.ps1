# ============================================================================
#  DeskBand -> Gitee 一键推送
#
#  用法：
#    1. 去 https://gitee.com/profile/personal_access_tokens 生成令牌
#       （描述随意，权限勾 projects，有效期建议 30 天）
#    2. 在本目录执行：  .\push-gitee.ps1
#    3. 提示时按 Ctrl+V 粘贴令牌、回车
#
#  为什么要有这个脚本（而不是让你敲一长串 git 命令）：
#    · 令牌用 SecureString 读入，**屏幕上不显示**，也**不进命令历史**
#    · 令牌只存在于本次进程内存里，不写任何文件
#    · 推完自动核对远程提交号是否与本地一致
#
#  等效的手工命令是：
#    git push "https://ninatokyoo:<令牌>@gitee.com/ninatokyoo/deskband.git" main
# ============================================================================

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$RepoUrl = 'https://gitee.com/ninatokyoo/deskband.git'
$User = 'ninatokyoo'
$WebUrl = 'https://gitee.com/ninatokyoo/deskband'

Write-Host ('=' * 62)
Write-Host ' DeskBand -> Gitee 推送'
Write-Host ('=' * 62)

# ---- 自检：把问题暴露在粘贴令牌之前 ----
if (-not (Test-Path '.git')) { Write-Host '✗ 当前目录不是 git 仓库，请先 cd 到项目目录'; exit 1 }

$branch = (git rev-parse --abbrev-ref HEAD).Trim()
$local  = (git rev-parse HEAD).Trim()
$dirty  = (git status --porcelain)

Write-Host "  分支      : $branch"
Write-Host "  本地提交  : $($local.Substring(0,12))"
if ($dirty) { Write-Host '  工作区    : 有未提交改动（不影响推送已提交内容）' }
else        { Write-Host '  工作区    : 干净' }
if ($branch -ne 'main') { Write-Host "⚠️  当前不在 main 分支，推送的会是 $branch" }

foreach ($pair in @(@('app/src/main/assets/pet','角色素材'), @('docs','开发截图'),
                    @('keystore','签名密钥库'), @('local.properties','本机配置'))) {
  if ((git ls-files $pair[0])) { Write-Host "⚠️  $($pair[1]) 竟然被跟踪了，建议先排除再推" }
}

Write-Host ('-' * 62)
$sec = Read-Host '粘贴 Gitee 令牌（不会显示），然后回车' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
try   { $token = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr).Trim() }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

if (-not $token) { Write-Host '✗ 没有输入令牌，已取消'; exit 1 }

$authUrl = "https://${User}:${token}@gitee.com/ninatokyoo/deskband.git"

Write-Host ('-' * 62)
Write-Host '正在推送……（首次要传 166 个提交，可能要一两分钟）'

# git 会把带凭据的 URL 打进 stderr，这里先把令牌抹掉再显示
$raw = (& git push $authUrl "${branch}:main" 2>&1 | Out-String)
$safe = $raw.Replace($token, '***').Replace($authUrl, $RepoUrl)
$code = $LASTEXITCODE

($safe.Trim() -split "`n" | Select-Object -Last 12) | ForEach-Object { Write-Host "  $_" }

if ($code -ne 0) {
  Write-Host ('-' * 62)
  Write-Host "✗ 推送失败（退出码 $code）。常见原因："
  Write-Host '   · 令牌权限没勾 projects    -> 重新生成一个'
  Write-Host '   · 复制时带了空格或换行      -> 重新复制一次'
  Write-Host '   · 令牌已过期                -> 重新生成'
  exit $code
}

Write-Host ('-' * 62)
Write-Host '✓ 推送成功，正在核对远程……'

$remote = (& git ls-remote $RepoUrl refs/heads/main 2>&1 | Out-String).Trim()
if ($remote) {
  $rh = ($remote -split '\s+')[0]
  Write-Host "  远程 main : $($rh.Substring(0,12))"
  Write-Host "  本地 HEAD : $($local.Substring(0,12))"
  if ($rh -eq $local) { Write-Host '✓ 两边一致，推送完整' }
  else                { Write-Host '⚠️ 两边不一致 —— 可能有别人同时推过，或推送被截断' }
} else {
  Write-Host '⚠️ 读不到远程 main（可能是私有仓库，或还没生效，稍等刷新页面看看）'
}

Write-Host ('-' * 62)
Write-Host "完成。打开看看： $WebUrl"
