# ============================================================================
#  DeskBand -> GitHub 一键推送
#
#  用法：
#    1. 先在 GitHub 网页上**建一个空仓库**（不要勾 README / .gitignore / license，
#       否则第一次 push 会因为"远程有本地没有的提交"被拒）：
#         https://github.com/new
#       名字建议 deskband，可见性按需（Public 才会出现在你的主页上）。
#
#    2. 生成访问令牌（GitHub 早已取消密码推送，必须用令牌）：
#         https://github.com/settings/tokens  ->  Generate new token (classic)
#       勾选 **repo** 一项即可；有效期自定。生成后**立刻复制**（只显示一次）。
#
#    3. 在本目录执行：
#         powershell -ExecutionPolicy Bypass -File .\push-github.ps1
#       如果你的 GitHub 用户名不是 ninatokyoo，加参数：
#         powershell -ExecutionPolicy Bypass -File .\push-github.ps1 -User 你的用户名
#
#    4. 提示时按 Ctrl+V 粘贴令牌、回车（输入不会显示）。
#
#  ⚠️ 令牌只走环境变量、不进 URL、不进命令行（原因见 tools\git-askpass.ps1 顶部）。
#  ⚠️ 这个脚本**只推不覆盖**：main 与当前分支各按原名推上去，不会强推、不会删远程分支。
# ============================================================================

param(
    [string]$User = 'ninatokyoo',
    [string]$Repo = 'deskband'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$RepoUrl = "https://github.com/$User/$Repo.git"
$WebUrl  = "https://github.com/$User/$Repo"
$AskPass = Join-Path $PSScriptRoot 'tools\git-askpass.ps1'

function Line { param($c = '-') Write-Host ($c * 62) }

Line '='
Write-Host ' DeskBand -> GitHub 推送'
Line '='

# ---- 自检：把问题暴露在粘贴令牌之前 ----
if (-not (Test-Path '.git')) { Write-Host 'x 当前目录不是 git 仓库，请先 cd 到项目目录'; exit 1 }
if (-not (Test-Path $AskPass)) { Write-Host "x 找不到凭据辅助脚本: $AskPass"; exit 1 }

$branch = (git rev-parse --abbrev-ref HEAD).Trim()
$local  = (git rev-parse HEAD).Trim()
$dirty  = (git status --porcelain)

Write-Host "  目标仓库  : $WebUrl"
Write-Host "  分支      : $branch（连同 main 一起推）"
Write-Host "  本地提交  : $($local.Substring(0,12))"
if ($dirty) { Write-Host '  工作区    : 有未提交改动（不影响推送已提交内容）' }
else        { Write-Host '  工作区    : 干净' }

# 这几个目录/文件不该进仓库，被跟踪了就提醒（密钥泄露是不可逆的）
foreach ($pair in @(@('app/src/main/assets/pet','角色素材'), @('docs','开发截图'),
                    @('keystore','签名密钥库'), @('local.properties','本机配置'))) {
  if ((git ls-files $pair[0])) { Write-Host "  注意：$($pair[1]) 竟然被跟踪了，建议先排除再推" }
}

# 远程是否已经存在（存在就直接用，不存在则添加）
$hasRemote = (git remote) -contains 'github'
if ($hasRemote) {
    git remote set-url github $RepoUrl
    Write-Host '  远程 github: 已存在，已更新地址'
} else {
    git remote add github $RepoUrl
    Write-Host '  远程 github: 已添加'
}

# 先探一下仓库能不能访问（没建仓库 / 名字写错时立刻能看出来，不用等粘贴令牌）
Line
Write-Host '正在检查远程仓库是否可用……'
$env:GIT_TERMINAL_PROMPT = '0'
$probe = (& git ls-remote $RepoUrl 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    ($probe.Trim() -split "`n" | Select-Object -Last 6) | ForEach-Object { Write-Host "  $_" }
    Write-Host 'x 访问不了这个地址。常见原因：'
    Write-Host "   - 仓库还没建（去 $WebUrl 建一个空仓库）"
    Write-Host '   - 用户名或仓库名拼错（-User / -Repo 参数）'
    Write-Host '   - 私有仓库需要令牌，先跑完下面的步骤再试'
    exit 1
}
Write-Host '  远程可访问'

Line
$sec = Read-Host '粘贴 GitHub 令牌（不会显示），然后回车' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
try   { $token = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr).Trim() }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

if (-not $token) { Write-Host 'x 没有输入令牌，已取消'; exit 1 }
$token = $token -replace '\s', ''      # 从网页复制常夹带换行/空格

Line
Write-Host '正在推送……（首次要传全部历史，这个仓库约 86MB，慢的话几分钟）'

$env:GIT_TOKEN = $token
$env:GIT_USER  = $User
$env:GIT_ASKPASS = $AskPass
$env:GIT_TERMINAL_PROMPT = '0'

$toPush = @('main')
if ($branch -ne 'main') { $toPush += $branch }

$all = ''
foreach ($b in $toPush) {
    $raw  = (& git push github "${b}:${b}" 2>&1 | Out-String)
    $code = $LASTEXITCODE
    $all += $raw
    if ($code -ne 0) {
        $safe = $all.Replace($token, '***')
        ($safe.Trim() -split "`n" | Select-Object -Last 12) | ForEach-Object { Write-Host "  $_" }
        Write-Host "x 推送 $b 失败（退出码 $code）。常见原因："
        Write-Host '   - 令牌没勾 repo 权限        -> 重新生成一个'
        Write-Host '   - 令牌过期/被撤销           -> 重新生成'
        Write-Host '   - 用户名不是这个            -> 用 -User 参数指定'
        Write-Host '   - 建仓库时勾了 README       -> 先在网页上删掉那个提交，或改用 git pull --rebase'
        Remove-Item Env:GIT_TOKEN -ErrorAction SilentlyContinue
        Remove-Item Env:GIT_ASKPASS -ErrorAction SilentlyContinue
        exit $code
    }
    Write-Host "  v $b 已推送"
}

# 保险：万一 git 把令牌回显出来，显示前先抹掉
$safe = $all.Replace($token, '***')
($safe.Trim() -split "`n" | Select-Object -Last 8) | ForEach-Object { Write-Host "  $_" }

Remove-Item Env:GIT_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:GIT_ASKPASS -ErrorAction SilentlyContinue

Line
Write-Host 'v 推送完成。接下来做「发行版」（把 APK 传上去给人下载）：'
Write-Host "   1. 打标签：  git tag v2.3.0 && git push github v2.3.0"
Write-Host "   2. 打开：    $WebUrl/releases/new"
Write-Host '   3. 选择刚推的标签 v2.3.0，标题写 DeskBand v2.3.0，'
Write-Host '      把说明里的「说明」8 条贴进正文，再把 APK 拖进附件框，点 Publish release。'
Line '='
