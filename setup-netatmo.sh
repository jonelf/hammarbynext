#!/usr/bin/env bash
# Lägger Netatmo-nycklarna från .env på TV:n, en gång. Appen läser in dem, sparar dem privat och tar bort filen.
# Usage: TV_HOST=<tv ip> ./setup-netatmo.sh
# .env behöver NETATMO_CLIENT_ID, NETATMO_CLIENT_SECRET och NETATMO_REFRESH_TOKEN (NETATMO_ACCESS_TOKEN valfri).
# Använder cleanyourtv:s ADB-klient: CLEANYOURTV pekar på repot (standard ~/projects/cleanyourtv).
set -euo pipefail
: "${TV_HOST:?sätt TV_HOST till TV:ns IP, t.ex. TV_HOST=192.168.1.50 $0}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CYT="${CLEANYOURTV:-$HOME/projects/cleanyourtv}"
PY="$CYT/tools/venv/bin/python"

val() { { grep -E "^$1=" "$HERE/.env" || true; } | tail -n 1 | cut -d= -f2- | sed -e 's/^["'\'']//' -e 's/["'\'']$//'; }
ID="$(val NETATMO_CLIENT_ID)"; SECRET="$(val NETATMO_CLIENT_SECRET)"; REFRESH="$(val NETATMO_REFRESH_TOKEN)"
ACCESS="$(val NETATMO_ACCESS_TOKEN)"
if [ -z "$ID" ] || [ -z "$SECRET" ] || [ -z "$REFRESH" ]; then
  echo "Saknas i .env: NETATMO_CLIENT_ID, NETATMO_CLIENT_SECRET och NETATMO_REFRESH_TOKEN behövs." >&2
  exit 1
fi

TMP="$(umask 077; mktemp)"
trap 'rm -f "$TMP"' EXIT
python3 -I - "$TMP" "$ID" "$SECRET" "$REFRESH" "$ACCESS" <<'PY'
import json, sys
path, cid, secret, refresh, access = sys.argv[1:]
json.dump({"client_id": cid, "client_secret": secret, "refresh_token": refresh, "access_token": access}, open(path, "w"))
PY

DIR=/sdcard/Android/data/se.hammarbynext.tv/files
TV_HOST="$TV_HOST" "$PY" -I "$CYT/tools/tv.py" "mkdir -p $DIR" >/dev/null
TV_HOST="$TV_HOST" "$PY" -I "$CYT/tools/tv.py" --push "$TMP" "$DIR/netatmo.json" >/dev/null
# Första frågan läser in nycklarna och startar en hämtning; Home visar vädret när den är klar.
TV_HOST="$TV_HOST" "$PY" -I "$CYT/tools/tv.py" "content query --uri content://se.hammarbynext.tv.games/info >/dev/null; sleep 6; ls $DIR/netatmo.json 2>/dev/null && echo 'Filen ligger kvar: appen läste inte in den.' || echo 'Nycklarna är inlästa.'; content query --uri content://se.hammarbynext.tv.games/info"
