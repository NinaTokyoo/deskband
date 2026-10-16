# git 凭据辅助脚本（由 GIT_ASKPASS 调用）
#
# 为什么需要它：把令牌拼进 URL（https://user:token@host/...）时，
# 令牌里只要出现 @ : / ? # 这类字符，URL 就会被解析坏，报
#   fatal: URL rejected: Malformed input to a URL function
#   fatal: ... Port number was not a decimal number ...
# 这是实测踩过的（第一次推送就栽在这上面）。
#
# 走 GIT_ASKPASS 就完全没有这个问题：令牌只通过**环境变量**传给 git，
# 不经过 URL 解析，特殊字符一律安全，也不会出现在命令行与日志里。
#
# git 会带着提示语调用本脚本，脚本把凭据打印到 stdout 即可：
#   提示里含 "Username" -> 打印用户名
#   其余（"Password"）  -> 打印令牌
#
# 环境变量：优先读通用的 GIT_USER / GIT_TOKEN（Gitee、GitHub 都用它），
# 读不到再回落到旧的 GITEE_USER / GITEE_TOKEN（老脚本仍能直接用）。

$prompt = $args[0]

if ($prompt -like '*Username*') {
    if ($env:GIT_USER) { Write-Output $env:GIT_USER } else { Write-Output $env:GITEE_USER }
    exit 0
}

if ($env:GIT_TOKEN) { Write-Output $env:GIT_TOKEN } else { Write-Output $env:GITEE_TOKEN }
exit 0
