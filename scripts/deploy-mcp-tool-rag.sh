#!/usr/bin/env bash
set -Eeuo pipefail

stage=${1:?usage: deploy-mcp-tool-rag.sh STAGE_DIR}
release=/home/ubuntu/starline-infra/releases/starline-20261022
envfile=/home/ubuntu/starline-infra/.env
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup=$release/backups/mcp-tool-rag-$stamp
activated=0
migration_started=0

compose() {
  sudo -n docker compose --env-file "$envfile" -p starline-20261022 -f "$release/starline-app.compose.yaml" "$@"
}

clear_intent_cache() {
  sudo -n docker exec starline-redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli DEL starlineagent:intent:tree' >/dev/null
}

rollback() {
  result=$?
  trap - ERR
  if [ "$activated" = 1 ]; then
    for file in starline.jar application.yaml starline-app.compose.yaml schema_pg.sql; do
      cp -p "$backup/$file" "$release/$file"
    done
    if [ -s "$backup/20261025_mcp_tool_registry.sql" ]; then
      cp -p "$backup/20261025_mcp_tool_registry.sql" "$release/20261025_mcp_tool_registry.sql"
    else
      rm -f "$release/20261025_mcp_tool_registry.sql"
    fi
    if [ -d "$release/frontend-dist" ]; then
      mv "$release/frontend-dist" "$stage/failed-frontend-$stamp"
    fi
    cp -a "$backup/frontend-dist" "$release/frontend-dist"
    if [ "$migration_started" = 1 ]; then
      sudo -n docker exec -i starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 <<'SQL' || true
UPDATE t_intent_node n
SET deleted = b.deleted_before, update_time = CURRENT_TIMESTAMP
FROM t_mcp_tool_intent_migration_backup b
WHERE n.id = b.intent_node_id;
SQL
      clear_intent_cache || true
    fi
    compose up -d --no-deps --force-recreate starline-api starline-web || true
  fi
  printf 'MCP_TOOL_RAG_DEPLOY_FAILED code=%s backup=%s\n' "$result" "$backup" >&2
  exit "$result"
}
trap rollback ERR

for file in starline.jar application.yaml starline-app.compose.yaml schema_pg.sql frontend.tar.gz 20261025_mcp_tool_registry.sql SHA256SUMS; do
  test -s "$stage/$file"
done
(cd "$stage" && sha256sum -c SHA256SUMS)
sudo -n docker compose --env-file "$envfile" -p starline-20261022 -f "$stage/starline-app.compose.yaml" config --quiet

mkdir -p "$backup"
chmod 700 "$backup"
sudo -n docker exec starline-postgres pg_dump -U starline -d starline -Fc --no-owner --no-privileges > "$backup/database.dump"
chmod 600 "$backup/database.dump"
test -s "$backup/database.dump"
sudo -n docker exec -i starline-postgres pg_restore -l < "$backup/database.dump" > "$backup/database-toc.txt"
for file in starline.jar application.yaml starline-app.compose.yaml schema_pg.sql; do
  cp -p "$release/$file" "$backup/$file"
done
cp -a "$release/frontend-dist" "$backup/frontend-dist"
if [ -s "$release/20261025_mcp_tool_registry.sql" ]; then
  cp -p "$release/20261025_mcp_tool_registry.sql" "$backup/20261025_mcp_tool_registry.sql"
fi

activated=1
migration_started=1
sudo -n docker exec -i starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 \
  < "$stage/20261025_mcp_tool_registry.sql" > "$backup/migration.log" 2>&1
sudo -n docker exec -i starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 \
  < "$stage/20261025_mcp_tool_registry.sql" > "$backup/migration-replay.log" 2>&1

mkdir -p "$stage/frontend-dist"
tar -xzf "$stage/frontend.tar.gz" -C "$stage/frontend-dist"
test -s "$stage/frontend-dist/index.html"

for file in starline.jar application.yaml starline-app.compose.yaml schema_pg.sql 20261025_mcp_tool_registry.sql; do
  cp "$stage/$file" "$release/$file.next"
  chmod 644 "$release/$file.next"
  mv -f "$release/$file.next" "$release/$file"
done
mv "$release/frontend-dist" "$backup/previous-frontend-dist"
mv "$stage/frontend-dist" "$release/frontend-dist"
find "$release/frontend-dist" -type d -exec chmod 755 {} +
find "$release/frontend-dist" -type f -exec chmod 644 {} +

old_api_id=$(sudo -n docker inspect --format '{{.Id}}' starline-api)
deployment_started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
clear_intent_cache
compose up -d --no-deps --force-recreate starline-api starline-web

connected=0
for attempt in $(seq 1 45); do
  api_state=$(sudo -n docker inspect --format '{{.State.Status}}' starline-api)
  api_id=$(sudo -n docker inspect --format '{{.Id}}' starline-api)
  if [ "$api_state" = running ] && [ "$api_id" != "$old_api_id" ] \
      && sudo -n docker logs --since "$deployment_started" starline-mcp 2>&1 \
        | grep -Eq 'Client initialize request.*Implementation\[name=agentscope-java'; then
    connected=1
    break
  fi
  sleep 2
done
test "$connected" = 1
tool_count=$(sudo -n docker exec starline-postgres psql -U starline -d starline -Atc \
  "SELECT COUNT(*) FROM t_mcp_tool WHERE deleted = 0 AND enabled = 1 AND dispatchable = 1")
test "$tool_count" -ge 1
sudo -n docker exec starline-web nginx -t
curl -fsS --connect-timeout 3 --max-time 8 -o /dev/null http://127.0.0.1/admin/mcp-tools
sudo -n docker inspect starline-api starline-web --format '{{.Name}} state={{.State.Status}} restarts={{.RestartCount}}'
sha256sum "$release/starline.jar" "$release/application.yaml" "$release/starline-app.compose.yaml" \
  "$release/schema_pg.sql" "$release/20261025_mcp_tool_registry.sql"
printf 'MCP_TOOL_RAG_DEPLOY_OK mcp_connected=%s dispatchable_tools=%s backup=%s\n' "$connected" "$tool_count" "$backup"
rm -f "$stage/frontend.tar.gz" "$stage/SHA256SUMS"
