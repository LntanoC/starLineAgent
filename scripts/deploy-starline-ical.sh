#!/usr/bin/env bash
set -Eeuo pipefail

release=/home/ubuntu/starline-infra/releases/starline-20261022
stage=$1
if [ -z "$stage" ]; then
  echo "usage: deploy-starline-ical.sh <release-stage-dir>" >&2
  exit 2
fi
case "$stage" in "$release"/.starline-ical-upload-*) ;; *) echo "Invalid staging directory" >&2; exit 2 ;; esac
test -d "$stage"
for file in starline.jar frontend-dist.tar.gz starline-app.compose.yaml nginx.conf application.yaml schema_pg.sql init_data_pg.sql 20261026_starline_ical.sql deploy-starline-ical.sh SHA256SUMS; do
  test -s "$stage/$file"
done
(cd "$stage" && sha256sum -c SHA256SUMS)
bash -n "$stage/deploy-starline-ical.sh"

stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$release/backups/starline-ical-$stamp"
next="$release/.frontend-dist-next-$stamp"
compose() {
  sudo -n docker compose --env-file /home/ubuntu/starline-infra/.env -p starline-20261022 -f "$release/starline-app.compose.yaml" "$@"
}

mkdir -p "$backup"
chmod 700 "$backup"
cp -p "$release/starline.jar" "$backup/starline.jar"
cp -a "$release/frontend-dist" "$backup/frontend-dist"
cp -p "$release/starline-app.compose.yaml" "$backup/starline-app.compose.yaml"
cp -p "$release/nginx.conf" "$backup/nginx.conf"
cp -p "$release/application.yaml" "$backup/application.yaml"
cp -p "$release/schema_pg.sql" "$backup/schema_pg.sql"
cp -p "$release/init_data_pg.sql" "$backup/init_data_pg.sql"
sudo -n docker exec starline-postgres pg_dump -U starline -d starline -Fc > "$backup/starline.dump"
test -s "$backup/starline.dump"

mkdir "$next"
tar -xzf "$stage/frontend-dist.tar.gz" -C "$next"
test -s "$next/index.html"
test -s "$stage/starline.jar"

activated=0
migration_applied=0
rollback() {
  result=$?
  trap - ERR
  if [ "$activated" = 1 ]; then
    compose stop starline-api starline-web || true
    cp -p "$backup/starline.jar" "$release/starline.jar"
    rm -rf "$release/frontend-dist"
    cp -a "$backup/frontend-dist" "$release/frontend-dist"
    cp -p "$backup/starline-app.compose.yaml" "$release/starline-app.compose.yaml"
    cp -p "$backup/nginx.conf" "$release/nginx.conf"
    cp -p "$backup/application.yaml" "$release/application.yaml"
    cp -p "$backup/schema_pg.sql" "$release/schema_pg.sql"
cp -p "$backup/init_data_pg.sql" "$release/init_data_pg.sql"
    if [ "$migration_applied" = 1 ]; then
      sudo -n docker exec starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 -c 'DROP TABLE IF EXISTS t_starline_calendar_token' || true
      sudo -n docker exec -i starline-postgres pg_restore --clean --if-exists --no-owner -U starline -d starline < "$backup/starline.dump" || true
    fi
    compose up -d --no-deps --force-recreate starline-api starline-web || true
  fi
  rm -rf "$next"
  printf 'STARLINE_ICAL_DEPLOY_FAILED backup=%s
' "$backup"
  exit "$result"
}
trap rollback ERR

activated=1
compose stop starline-api starline-web
sudo -n docker exec -i starline-postgres psql -U starline -d starline -v ON_ERROR_STOP=1 < "$stage/20261026_starline_ical.sql"
migration_applied=1

cp "$stage/starline.jar" "$release/starline.jar.next"
chmod 644 "$release/starline.jar.next"
mv -f "$release/starline.jar.next" "$release/starline.jar"
mv "$release/frontend-dist" "$release/frontend-dist.previous-$stamp"
mv "$next" "$release/frontend-dist"
cp "$stage/starline-app.compose.yaml" "$release/starline-app.compose.yaml"
cp "$stage/nginx.conf" "$release/nginx.conf"
cp "$stage/application.yaml" "$release/application.yaml"
cp "$stage/schema_pg.sql" "$release/schema_pg.sql"
cp "$stage/init_data_pg.sql" "$release/init_data_pg.sql"
cp "$stage/20261026_starline_ical.sql" "$release/20261026_starline_ical.sql"
cp "$stage/deploy-starline-ical.sh" "$release/deploy-starline-ical.sh"
chmod 755 "$release/deploy-starline-ical.sh"

# Drop only retired mail settings; never print or copy the environment file.
sed -i -E '/^(STARLINE_EMAIL_[A-Z0-9_]*|SPRING_MAIL_[A-Z0-9_]*)=/d' /home/ubuntu/starline-infra/.env
compose config -q
sudo -n docker run --rm --network none --add-host starline-api:127.0.0.1 -v "$release/nginx.conf:/etc/nginx/conf.d/default.conf:ro" nginx:alpine nginx -t
compose up -d --no-deps --force-recreate starline-api starline-web

ready=0
for attempt in $(seq 1 60); do
  web_code=$(curl -sS --connect-timeout 2 --max-time 5 -o /dev/null -w '%{http_code}' http://127.0.0.1/ 2>/dev/null || true)
  calendar_code=$(curl -sS --connect-timeout 2 --max-time 5 -o /dev/null -w '%{http_code}' http://127.0.0.1/api/starlineagent/starline/calendar.ics 2>/dev/null || true)
  if [ "$web_code" = 200 ] && [ "$calendar_code" = 401 ]; then
    ready=1
    break
  fi
  sleep 2
done
test "$ready" = 1
sudo -n docker inspect starline-api starline-web --format '{{.Name}} state={{.State.Status}} restarts={{.RestartCount}}'
sudo -n docker exec starline-postgres psql -U starline -d starline -Atc "SELECT 'event_columns=' || count(*) FROM information_schema.columns WHERE table_name='t_starline_event_reminder'; SELECT 'calendar_token_table=' || to_regclass('public.t_starline_calendar_token');"
jar_hash=$(sha256sum "$release/starline.jar" | cut -d ' ' -f1)
stage_hash=$(sha256sum "$stage/starline.jar" | cut -d ' ' -f1)
test "$jar_hash" = "$stage_hash"
rm -rf "$release/frontend-dist.previous-$stamp" "$stage"
trap - ERR
printf 'STARLINE_ICAL_DEPLOY_OK backup=%s jar_sha256=%s
' "$backup" "$jar_hash"
