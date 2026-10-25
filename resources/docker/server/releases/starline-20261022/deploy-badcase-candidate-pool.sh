#!/usr/bin/env bash
set -Eeuo pipefail
release=/home/ubuntu/starline-infra/releases/starline-20261022
stage=$release/.badcase-pool-upload-20261025
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup=$release/backups/badcase-pool-$stamp
activated=0
compose(){ sudo -n docker compose --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 -f "$release/starline-app.compose.yaml" "$@"; }
rollback(){
 result=$?;trap - ERR
 if [ "$activated" = 1 ];then
  for file in starline.jar application.yaml nginx.conf schema_pg.sql;do cp -p "$backup/$file" "$release/$file";done
  if [ -d "$release/frontend-dist" ];then mv "$release/frontend-dist" "$stage/failed-frontend-$stamp";fi
  cp -a "$backup/frontend-dist" "$release/frontend-dist"
  compose up -d --no-deps --force-recreate starline-api starline-web || true
 fi
 printf 'BADCASE_DEPLOY_FAILED code=%s backup=%s\n' "$result" "$backup" >&2;exit "$result"
}
trap rollback ERR
for file in starline.jar application.yaml nginx.conf schema_pg.sql frontend.tar.gz 20261025_bad_case_candidate_pool.sql verify-badcase-candidate-pool.py;do test -s "$stage/$file";done
(cd "$stage" && sha256sum -c SHA256SUMS)
mkdir -p "$backup";chmod 700 "$backup"
sudo -n docker exec starline-postgres pg_dump -U starline -d starline -Fc --no-owner --no-privileges > "$backup/database.dump"
chmod 600 "$backup/database.dump"
test -s "$backup/database.dump"
sudo -n docker exec -i starline-postgres pg_restore -l < "$backup/database.dump" > "$backup/database-toc.txt"
for file in starline.jar application.yaml nginx.conf schema_pg.sql;do cp -p "$release/$file" "$backup/$file";done
cp -a "$release/frontend-dist" "$backup/frontend-dist"
sudo -n docker exec -i starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 < "$stage/20261025_bad_case_candidate_pool.sql"
sudo -n docker exec -i starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 # Verify a replay before replacing application files.
sudo -n docker exec -i starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 < "$stage/20261025_bad_case_candidate_pool.sql" > "$backup/migration-replay.log" 2>&1
mkdir -p "$stage/frontend-dist";tar -xzf "$stage/frontend.tar.gz" -C "$stage/frontend-dist";test -s "$stage/frontend-dist/index.html"
activated=1
for file in starline.jar application.yaml nginx.conf schema_pg.sql;do cp "$stage/$file" "$release/$file.next";chmod 644 "$release/$file.next";mv -f "$release/$file.next" "$release/$file";done
mv "$release/frontend-dist" "$stage/previous-frontend";mv "$stage/frontend-dist" "$release/frontend-dist"
find "$release/frontend-dist" -type d -exec chmod 755 {} +
find "$release/frontend-dist" -type f -exec chmod 644 {} +
compose up -d --no-deps --force-recreate starline-api starline-web
ready(){
 for attempt in $(seq 1 45);do
  if curl -fsS --connect-timeout 2 --max-time 3 http://127.0.0.1:9090/api/starlineagent/agent/v1/meta >/dev/null 2>&1;then return 0;fi
  sleep 2
 done
 return 1
}
ready
sudo -n docker exec starline-web nginx -t
python3 "$stage/verify-badcase-candidate-pool.py" | tee "$backup/shadow-verification.json"
# The tested high-confidence entrances graduate from SHADOW after the shadow probe succeeds.
python3 - "$release/application.yaml" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]);s=p.read_text()
for code in ('agent-runtime','user-correction','starline-business'):
 old=code+": {mode: 'SHADOW'}"
 assert old in s, 'rollout mode mismatch: '+code
 s=s.replace(old,code+": {mode: 'ON'}",1)
p.write_text(s)
PY
compose up -d --no-deps --force-recreate starline-api
ready
python3 "$stage/verify-badcase-candidate-pool.py" | tee "$backup/production-verification.json"
for file in 20261025_bad_case_candidate_pool.sql deploy-badcase-candidate-pool.sh verify-badcase-candidate-pool.py;do cp "$stage/$file" "$release/$file";done
sudo -n docker inspect starline-api starline-web --format '{{.Name}} state={{.State.Status}} restarts={{.RestartCount}}'
sha256sum "$release/starline.jar" "$release/application.yaml" "$release/nginx.conf" "$release/schema_pg.sql"
printf 'BADCASE_DEPLOY_OK backup=%s\n' "$backup"
