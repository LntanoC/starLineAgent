#!/usr/bin/env bash
set -Eeuo pipefail
release=/home/ubuntu/starline-infra/releases/starline-20261022
stage=${1:?usage: deploy-feedback-withdrawal.sh <release-stage-dir>}
case "$stage" in "$release"/.feedback-withdrawal-upload-*) ;; *) echo "Invalid staging directory" >&2; exit 2 ;; esac
test -d "$stage"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$release/backups/feedback-withdrawal-$stamp"
previous="$release/.frontend-dist-previous-$stamp"
next="$release/.frontend-dist-next-$stamp"
failed="$release/.frontend-dist-failed-$stamp"
compose(){ sudo -n docker compose --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 -f "$release/starline-app.compose.yaml" "$@"; }
mkdir -p "$backup"
chmod 700 "$backup"
cp -p "$release/starline.jar" "$backup/starline.jar"
cp -a "$release/frontend-dist" "$backup/frontend-dist"
(cd "$stage" && sha256sum -c SHA256SUMS)
mkdir "$next"
tar -xzf "$stage/frontend.tar.gz" -C "$next"
test -s "$next/index.html"
test -s "$stage/starline.jar"
activated=0
rollback(){
  result=$?
  trap - ERR
  if [ "$activated" = 1 ]; then
    cp -p "$backup/starline.jar" "$release/starline.jar.next" || true
    if [ -f "$release/starline.jar.next" ]; then mv -f "$release/starline.jar.next" "$release/starline.jar"; fi
    if [ -d "$previous" ]; then
      if [ -d "$release/frontend-dist" ]; then mv "$release/frontend-dist" "$failed" || true; fi
      mv "$previous" "$release/frontend-dist" || true
      if [ -d "$failed" ]; then rm -rf "$failed"; fi
    fi
    compose up -d --no-deps --force-recreate starline-api starline-web || true
  fi
  printf 'FEEDBACK_WITHDRAWAL_FAILED backup=%s\n' "$backup"
  exit "$result"
}
trap rollback ERR
activated=1
mv "$release/frontend-dist" "$previous"
mv "$next" "$release/frontend-dist"
cp "$stage/starline.jar" "$release/starline.jar.next"
chmod 644 "$release/starline.jar.next"
mv -f "$release/starline.jar.next" "$release/starline.jar"
compose up -d --no-deps --force-recreate starline-api starline-web
ready=0
for attempt in $(seq 1 45); do
  if curl -fsS --connect-timeout 2 --max-time 3 http://127.0.0.1:9090/api/starlineagent/agent/v1/meta >/dev/null 2>&1 && curl -fsS --connect-timeout 2 --max-time 3 http://127.0.0.1/ | grep -q '/assets/index-'; then
    ready=1
    break
  fi
  sleep 2
done
test "$ready" = 1
actual_jar=$(sha256sum "$release/starline.jar" | cut -d ' ' -f1)
expected_jar=$(sha256sum "$stage/starline.jar" | cut -d ' ' -f1)
test "$actual_jar" = "$expected_jar"
sudo -n docker inspect starline-api starline-web --format '{{.Name}} state={{.State.Status}} restarts={{.RestartCount}}'
printf '%s  %s\n' "$actual_jar" "$release/starline.jar"
cp "$stage/deploy-feedback-withdrawal.sh" "$release/deploy-feedback-withdrawal.sh"
chmod 755 "$release/deploy-feedback-withdrawal.sh"
rm -rf "$previous" "$stage"
trap - ERR
printf 'FEEDBACK_WITHDRAWAL_DEPLOY_OK backup=%s\n' "$backup"
