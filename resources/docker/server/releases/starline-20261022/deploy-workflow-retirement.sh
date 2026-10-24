#!/usr/bin/env bash
set -Eeuo pipefail
release=/home/ubuntu/starline-infra/releases/starline-20261022
stage=$release/.workflow-upload-20261024
backup=$(cat "$stage/backup-location.txt")
case "$backup" in "$release"/backups/workflow-retire-*) ;; *) exit 2 ;; esac
activated=0
dropped=0
compose() { sudo -n docker compose --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 -f "$release/starline-app.compose.yaml" "$@"; }
rollback() {
  result=$?
  trap - ERR
  if [ "$dropped" = 1 ]; then
    sudo -n docker exec -i starline-postgres pg_restore -U starline -d starline --no-owner --no-privileges --exit-on-error --single-transaction < "$backup/workflow-tables.dump" || true
  fi
  if [ "$activated" = 1 ]; then
    sudo -n docker logs --tail 180 starline-api 2>&1 | grep -E 'APPLICATION FAILED|Error creating bean|Caused by:|No qualifying bean|Started StarlineApplication' > "$backup/startup-errors.txt" || true
    chmod 600 "$backup/startup-errors.txt"
    cat "$backup/startup-errors.txt"
    for f in starline.jar schema_pg.sql application.yaml starline-app.compose.yaml; do
      cp -p "$backup/$f" "$release/$f.rollback"
      mv -f "$release/$f.rollback" "$release/$f"
    done
    if [ -d "$backup/frontend-dist" ]; then
      if [ -d "$release/frontend-dist" ]; then mv "$release/frontend-dist" "$stage/failed-frontend-$(date +%s)"; fi
      cp -a "$backup/frontend-dist" "$release/frontend-dist"
    fi
    compose up -d --no-deps --force-recreate starline-api starline-web || true
  fi
  printf 'WORKFLOW_DEPLOY_FAILED code=%s backup=%s\n' "$result" "$backup"
  exit "$result"
}
trap rollback ERR
for f in starline.jar frontend.tar.gz schema_pg.sql application.yaml starline-app.compose.yaml 20261024_workflow_retire.sql verify-workflow-retirement.py verify-agent-audit-server.py; do test -s "$stage/$f"; done
test -s "$backup/workflow-tables.dump"
sudo -n docker exec -i starline-postgres pg_restore -l < "$backup/workflow-tables.dump" | grep -q 'TABLE DATA public t_message'
sudo -n docker compose --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 -f "$stage/starline-app.compose.yaml" config --quiet
for f in starline.jar schema_pg.sql application.yaml starline-app.compose.yaml; do cp -p "$release/$f" "$backup/$f"; done
mkdir -p "$stage/frontend-dist"
tar -xzf "$stage/frontend.tar.gz" -C "$stage/frontend-dist"
test -s "$stage/frontend-dist/index.html"
activated=1
for f in starline.jar schema_pg.sql application.yaml starline-app.compose.yaml; do
  cp "$stage/$f" "$release/$f.next"
  chmod 644 "$release/$f.next"
  mv -f "$release/$f.next" "$release/$f"
done
mv "$release/frontend-dist" "$backup/frontend-dist"
mv "$stage/frontend-dist" "$release/frontend-dist"
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
python3 "$stage/verify-workflow-retirement.py" pre
sudo -n docker exec -i starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 < "$stage/20261024_workflow_retire.sql"
dropped=1
python3 "$stage/verify-workflow-retirement.py" post
for f in 20261024_workflow_retire.sql deploy-workflow-retirement.sh verify-workflow-retirement.py; do cp "$stage/$f" "$release/$f"; done
sha256sum "$release/starline.jar" "$release/application.yaml" "$release/schema_pg.sql" "$release/starline-app.compose.yaml"
sudo -n docker inspect starline-api starline-web --format '{{.Name}} state={{.State.Status}} restarts={{.RestartCount}}'
printf 'WORKFLOW_RETIRED_OK backup=%s\n' "$backup"
