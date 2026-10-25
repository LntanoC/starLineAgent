#!/usr/bin/env bash
set -Eeuo pipefail

release_dir=/home/ubuntu/starline-infra/releases/starline-20261022
stage_dir=${1:?usage: deploy-mcp-server.sh <stage-directory>}
case "$stage_dir" in
  "$release_dir"/.mcp-stage-*) ;;
  *) echo "拒绝使用非预期暂存目录" >&2; exit 2 ;;
esac
test "$(dirname "$stage_dir")" = "$release_dir"
case "$(basename "$stage_dir")" in
  .mcp-stage-*) ;;
  *) echo "拒绝清理非 MCP 暂存目录" >&2; exit 2 ;;
esac
test -d "$stage_dir"
test -s "$stage_dir/mcp-server.jar"
test -s "$stage_dir/starline-app.compose.yaml"
test -s "$stage_dir/SHA256SUMS"
test -s "$stage_dir/deploy-mcp-server.sh"

(cd "$stage_dir" && sha256sum -c SHA256SUMS)
grep -qE '^  starline-mcp:' "$stage_dir/starline-app.compose.yaml"
services=$(sudo -n docker compose \
  --project-directory "$release_dir" \
  --env-file /home/ubuntu/starline-infra/.env \
  -p starline-20261022 \
  -f "$stage_dir/starline-app.compose.yaml" config --services)
printf '%s\n' "$services" | grep -qx starline-mcp

backup_dir="$release_dir/backups/mcp-deploy-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 700 -- "$backup_dir"
cp -p "$release_dir/starline-app.compose.yaml" "$backup_dir/starline-app.compose.yaml"
jar_preexisting=0
if [ -f "$release_dir/mcp-server.jar" ]; then
  cp -p "$release_dir/mcp-server.jar" "$backup_dir/mcp-server.jar"
  jar_preexisting=1
fi

activated=0
rollback() {
  result=$?
  trap - ERR
  if [ "$activated" = 1 ]; then
    sudo -n docker rm -f starline-mcp >/dev/null 2>&1 || true
    cp -p "$backup_dir/starline-app.compose.yaml" "$release_dir/starline-app.compose.yaml.rollback"
    mv -f "$release_dir/starline-app.compose.yaml.rollback" "$release_dir/starline-app.compose.yaml"
    if [ "$jar_preexisting" = 1 ]; then
      cp -p "$backup_dir/mcp-server.jar" "$release_dir/mcp-server.jar.rollback"
      mv -f "$release_dir/mcp-server.jar.rollback" "$release_dir/mcp-server.jar"
    else
      rm -f "$release_dir/mcp-server.jar"
    fi
    if grep -qE '^  starline-mcp:' "$backup_dir/starline-app.compose.yaml"; then
      sudo -n docker compose --project-directory "$release_dir" \
        --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 \
        -f "$release_dir/starline-app.compose.yaml" up -d --no-deps starline-mcp || true
    fi
  fi
  echo "MCP_DEPLOY_FAILED backup=$backup_dir" >&2
  exit "$result"
}
trap rollback ERR

activated=1
install -m 0644 "$stage_dir/mcp-server.jar" "$release_dir/mcp-server.jar.next"
install -m 0644 "$stage_dir/starline-app.compose.yaml" "$release_dir/starline-app.compose.yaml.next"
mv -f "$release_dir/mcp-server.jar.next" "$release_dir/mcp-server.jar"
mv -f "$release_dir/starline-app.compose.yaml.next" "$release_dir/starline-app.compose.yaml"

compose=(sudo -n docker compose --project-directory "$release_dir" \
  --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 \
  -f "$release_dir/starline-app.compose.yaml")
"${compose[@]}" up -d --no-deps --force-recreate starline-mcp

ready=0
for attempt in $(seq 1 30); do
  state=$(sudo -n docker inspect --format '{{.State.Status}}' starline-mcp 2>/dev/null || true)
  logs=$(sudo -n docker logs starline-mcp 2>&1 || true)
  if [ "$state" = running ] && [[ "$logs" == *"Started McpServerApplication"* ]]; then
    ready=1
    break
  fi
  sleep 2
done
test "$ready" = 1

sudo -n docker inspect starline-mcp --format '{{.Name}} state={{.State.Status}} restarts={{.RestartCount}}'
printf 'MCP_DEPLOY_OK backup=%s\n' "$backup_dir"
rm -rf -- "$stage_dir"
trap - ERR
