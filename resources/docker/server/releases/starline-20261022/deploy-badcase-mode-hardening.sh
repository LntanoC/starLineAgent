#!/usr/bin/env bash
set -Eeuo pipefail
release=/home/ubuntu/starline-infra/releases/starline-20261022
stage=$release/.badcase-pool-upload-20261025
backup=$release/backups/badcase-mode-hardening-$(date -u +%Y%m%dT%H%M%SZ)
compose(){ sudo -n docker compose --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 -f "$release/starline-app.compose.yaml" "$@"; }
mkdir -p "$backup";chmod 700 "$backup"
cp -p "$release/starline.jar" "$backup/starline.jar"
cp -p "$release/application.yaml" "$backup/application.yaml"
test "$(sha256sum "$stage/starline.jar" | cut -d ' ' -f1)" = "$1"
activated=0
rollback(){ result=$?;trap - ERR;if [ "$activated" = 1 ];then cp "$backup/starline.jar" "$release/starline.jar.next";mv -f "$release/starline.jar.next" "$release/starline.jar";compose up -d --no-deps --force-recreate starline-api || true;fi;printf 'MODE_HARDENING_FAILED backup=%s\n' "$backup";exit "$result"; }
trap rollback ERR
cp "$stage/starline.jar" "$release/starline.jar.next";chmod 644 "$release/starline.jar.next"
activated=1;mv -f "$release/starline.jar.next" "$release/starline.jar"
compose up -d --no-deps --force-recreate starline-api
ready=0
for attempt in $(seq 1 45);do if curl -fsS --connect-timeout 2 --max-time 3 http://127.0.0.1:9090/api/starlineagent/agent/v1/meta >/dev/null 2>&1;then ready=1;break;fi;sleep 2;done
test "$ready" = 1
python3 "$stage/verify-badcase-candidate-pool.py" | tee "$backup/verification.json"
sudo -n docker exec starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 -c "DELETE FROM t_bad_case_sample_batch b WHERE b.frame_count=0 AND b.selected_count=0 AND NOT EXISTS(SELECT 1 FROM t_bad_case_sample_member m WHERE m.batch_id=b.id); SELECT count(*) AS leftover_test_schemas FROM pg_namespace WHERE nspname LIKE 'bc_pool_it_%' OR nspname LIKE 'bc_runtime_it_%';"
for file in verify-badcase-candidate-pool.py deploy-badcase-candidate-pool.sh deploy-badcase-mode-hardening.sh;do cp "$stage/$file" "$release/$file";done
sha256sum "$release/starline.jar" "$release/application.yaml" "$release/nginx.conf"
sudo -n docker inspect starline-api starline-web --format '{{.Name}} state={{.State.Status}} restarts={{.RestartCount}}'
printf 'MODE_HARDENING_OK backup=%s\n' "$backup"
