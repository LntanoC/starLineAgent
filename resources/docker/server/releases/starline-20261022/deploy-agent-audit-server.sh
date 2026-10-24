#!/usr/bin/env bash
set -Eeuo pipefail
release=/home/ubuntu/starline-infra/releases/starline-20261022
stage=$release/.audit-upload-20261024
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup=$release/backups/agent-audit-$stamp
activated=0
compose() { sudo -n docker compose --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 -f "$release/starline-app.compose.yaml" "$@"; }
rollback() {
  result=$?
  trap - ERR
  if [ "$activated" = 1 ]; then
    sudo -n docker logs --tail 250 starline-api 2>&1 | grep -E 'APPLICATION FAILED|Description:|Parameter |defined in|Error creating bean|Caused by:|No qualifying bean|Consider |AgentAudit|Started StarlineApplication' | sed -E 's/((password|token|secret|api.?key)[=: ]+)[^ ,;]+/\1[REDACTED]/Ig' > "$backup/startup-errors.txt" || true
    chmod 600 "$backup/startup-errors.txt"
    cat "$backup/startup-errors.txt"
    cp -p "$backup/starline.jar" "$release/starline.jar"
    cp -p "$backup/starline-app.compose.yaml" "$release/starline-app.compose.yaml"
    if [ -s "$backup/application.yaml" ]; then cp -p "$backup/application.yaml" "$release/application.yaml"; fi
    if [ -d "$release/frontend-dist" ]; then mv "$release/frontend-dist" "$stage/failed-frontend-$stamp"; fi
    cp -a "$backup/frontend-dist" "$release/frontend-dist"
    compose up -d --no-deps --force-recreate starline-api starline-web || true
  fi
  printf 'DEPLOY_FAILED code=%s backup=%s\n' "$result" "$backup" >&2
  exit "$result"
}
trap rollback ERR
test -s "$stage/starline.jar"
test -s "$stage/frontend.tar.gz"
test -s "$stage/20261024_agent_run.sql"
test -s "$stage/schema_pg.sql"
test -s "$stage/application.yaml"
test -s "$stage/starline-app.compose.yaml"
sudo -n docker compose --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 -f "$stage/starline-app.compose.yaml" config --quiet
mkdir -p "$backup"
chmod 700 "$backup"
sudo -n docker exec starline-postgres pg_dump -U starline -d starline -Fc --no-owner --no-privileges > "$backup/database.dump"
chmod 600 "$backup/database.dump"
cp -p "$release/starline.jar" "$backup/starline.jar"
cp -p "$release/schema_pg.sql" "$backup/schema_pg.sql"
cp -p "$release/starline-app.compose.yaml" "$backup/starline-app.compose.yaml"
if [ -s "$release/application.yaml" ]; then cp -p "$release/application.yaml" "$backup/application.yaml"; fi
sudo -n docker exec -i starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 < "$stage/20261024_agent_run.sql"
sudo -n docker exec -i starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 < "$stage/20261024_agent_run_verify.sql"
mkdir -p "$stage/frontend-dist"
tar -xzf "$stage/frontend.tar.gz" -C "$stage/frontend-dist"
test -s "$stage/frontend-dist/index.html"
cp "$stage/schema_pg.sql" "$release/schema_pg.sql"
cp "$stage/20261024_agent_run.sql" "$release/20261024_agent_run.sql"
cp "$stage/20261024_agent_run_verify.sql" "$release/20261024_agent_run_verify.sql"
activated=1
cp "$stage/starline-app.compose.yaml" "$release/starline-app.compose.yaml"
cp "$stage/application.yaml" "$release/application.yaml"
chmod 644 "$release/application.yaml"
mv "$release/frontend-dist" "$backup/frontend-dist"
mv "$stage/frontend-dist" "$release/frontend-dist"
cp "$stage/starline.jar" "$release/starline.jar"
chmod 644 "$release/starline.jar" "$release/schema_pg.sql"
find "$release/frontend-dist" -type d -exec chmod 755 {} +
find "$release/frontend-dist" -type f -exec chmod 644 {} +
compose up -d --no-deps --force-recreate starline-api starline-web
code=000
for attempt in $(seq 1 45); do
  code=$(curl -sS --connect-timeout 2 --max-time 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:9090/api/starlineagent/agent/v1/meta 2>/dev/null || true)
  if [ "$code" = 200 ]; then break; fi
  sleep 2
done
test "$code" = 200
sudo -n docker exec starline-web nginx -t
curl -fsS --connect-timeout 3 --max-time 8 -o /dev/null http://127.0.0.1/admin/agent-runs
sudo -n docker inspect starline-api starline-web --format '{{.Name}} state={{.State.Status}} restarts={{.RestartCount}}'
sha256sum "$release/starline.jar" "$release/schema_pg.sql"
stat -c '%a %n' "$backup/database.dump"
printf 'DEPLOY_OK api_http=%s backup=%s\n' "$code" "$backup"
rm -f "$stage/frontend.tar.gz"
