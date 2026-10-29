#!/usr/bin/env bash
# 发版后验证：确认 GitHub latest release 的 version.json 附件已上传且版本号与本地一致。
# 走 App 检查更新同款链路（镜像优先+直连兜底），不查 api.github.com，不受匿名限流影响。
#
# 用法:
#   bash scripts/verify_release_assets.sh               # 直连+镜像自动轮询
#   bash scripts/verify_release_assets.sh http://127.0.0.1:7897   # 指定 HTTP 代理(可选)
#
# 退出码: 0=验证通过  1=漏传/版本不一致(发版不完整,立即补传后重跑)
set -u
cd "$(dirname "$0")/.."

PROXY="${1:-${HTTPS_PROXY:-}}"
REPO="qianxuntudou-ops/MyTVB"
URL="https://github.com/$REPO/releases/latest/download/version.json"
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

# 与 ApkUpdater.DOWNLOAD_MIRRORS 保持一致
CANDIDATES=(
  "https://ghfast.top/$URL"
  "https://gh.ddlc.top/$URL"
  "https://ghproxy.net/$URL"
  "$URL"
)

proxy_args=()
[ -n "$PROXY" ] && proxy_args=(-x "$PROXY")

# 本地期望版本(仓库根 version.json,发版时应已更新)
local_version=$(grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' version.json 2>/dev/null | head -1 | sed 's/.*"\([0-9][0-9.]*\)"/\1/')
if [ -z "$local_version" ]; then
  echo "FAIL: 本地 version.json 缺失或没有 version 字段"
  exit 1
fi

echo "本地期望版本: v$local_version"
remote_body=""
for c in "${CANDIDATES[@]}"; do
  code=$(curl -s -o /tmp/rel_vjson.$$ -w "%{http_code}" -L --max-time 20 -A "$UA" "${proxy_args[@]}" "$c" 2>/dev/null)
  if [ "$code" = "200" ]; then
    remote_body=$(cat /tmp/rel_vjson.$$)
    rm -f /tmp/rel_vjson.$$
    echo "拉取成功 via: ${c:0:60}"
    break
  fi
  echo "  候选不可用($code): ${c:0:52}..."
  rm -f /tmp/rel_vjson.$$
done

if [ -z "$remote_body" ]; then
  echo "FAIL: 所有链路都拉不到 version.json —— latest release 大概率漏传了该附件,"
  echo "      到 Release 页面把仓库根的 version.json 补传为附件后重跑本脚本。"
  exit 1
fi

remote_version=$(echo "$remote_body" | grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([0-9][0-9.]*\)"/\1/')
remote_apk=$(echo "$remote_body" | grep -o '"apk"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*:[[:space:]]*"\([^"]*\)"/\1/')
echo "线上 version.json: v$remote_version  apk=$remote_apk"

if [ "$remote_version" != "$local_version" ]; then
  echo "FAIL: 版本不一致(本地 v$local_version vs 线上 v$remote_version) —— Release 尚未发布为最新版或附件是旧的"
  exit 1
fi

echo "PASS: latest release 的 version.json 已上传且与本地一致,App 检查更新链路可用"
exit 0
