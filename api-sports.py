#!/usr/bin/env python3
"""
Visar Hammarbys (herr) nästa match + nedräkning, och de närmaste matcherna efter den.

Data kommer från ESPN:s öppna JSON-API. Ingen nyckel behövs, men API:t är
inofficiellt och odokumenterat, så det kan ändras utan förvarning.

Användning:
    python3 api-sports.py

Kräver Python 3.9+ och inga extra paket.
"""

import json
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

HAMMARBY_ESPN_ID = 2495
# "all" ger matcher i alla turneringar (liga, cup, Europa), inte bara Allsvenskan.
SCHEDULE_URL = (
    "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/"
    f"{HAMMARBY_ESPN_ID}/schedule?fixture=true"
)
LOCAL_TZ = ZoneInfo("Europe/Stockholm")
UPCOMING_TO_SHOW = 4  # antal matcher att lista efter nästa match

WEEKDAYS = ["måndag", "tisdag", "onsdag", "torsdag", "fredag", "lördag", "söndag"]
MONTHS = ["januari", "februari", "mars", "april", "maj", "juni", "juli",
          "augusti", "september", "oktober", "november", "december"]


def fetch_schedule() -> list:
    req = urllib.request.Request(SCHEDULE_URL, headers={"User-Agent": "hammarbynext/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.load(resp)
    except (urllib.error.URLError, json.JSONDecodeError) as e:
        print(f"Kunde inte hämta spelschemat från ESPN: {e}")
        sys.exit(1)
    return data.get("events", [])


def parse_event(event: dict) -> dict:
    comp = event["competitions"][0]
    teams = {c["homeAway"]: c["team"]["displayName"] for c in comp["competitors"]}
    kickoff_utc = datetime.fromisoformat(event["date"].replace("Z", "+00:00"))
    return {
        "kickoff_utc": kickoff_utc,
        "home": teams.get("home", "?"),
        "away": teams.get("away", "?"),
        "venue": (comp.get("venue") or {}).get("fullName") or "okänd arena",
        "league": (event.get("league") or {}).get("name") or "okänd turnering",
        "status": comp["status"]["type"].get("description", ""),
        "state": comp["status"]["type"].get("state", ""),  # pre / in / post
    }


def format_date(dt: datetime) -> str:
    return f"{WEEKDAYS[dt.weekday()]} {dt.day} {MONTHS[dt.month - 1]} {dt.year} kl. {dt:%H:%M}"


def format_countdown(delta) -> str:
    total = int(delta.total_seconds())
    if total <= 0:
        return "matchen har startat eller är spelad"
    days, rest = divmod(total, 86400)
    hours, rest = divmod(rest, 3600)
    minutes = rest // 60
    return f"{days} d {hours} h {minutes} min"


def main() -> None:
    now = datetime.now(timezone.utc)
    matches = [parse_event(e) for e in fetch_schedule()]
    # Kommande eller pågående matcher, sorterade på avspark.
    upcoming = sorted(
        (m for m in matches if m["state"] in ("pre", "in")),
        key=lambda m: m["kickoff_utc"],
    )

    if not upcoming:
        print("Inga kommande matcher hittades. Säsongen kan vara slut.")
        return

    nxt = upcoming[0]
    kickoff_local = nxt["kickoff_utc"].astimezone(LOCAL_TZ)
    print("=== Nästa match ===")
    print(f"{nxt['home']} – {nxt['away']}")
    print(f"{nxt['league']}, {nxt['venue']}")
    print(f"Avspark: {format_date(kickoff_local)} (svensk tid)")
    print(f"Status: {nxt['status']}")
    if nxt["state"] == "in":
        print("Matchen pågår just nu!")
    else:
        print(f"Nedräkning: {format_countdown(nxt['kickoff_utc'] - now)}")

    later = upcoming[1:1 + UPCOMING_TO_SHOW]
    if later:
        print("\n=== Därefter ===")
        for m in later:
            local = m["kickoff_utc"].astimezone(LOCAL_TZ)
            print(f"{local:%Y-%m-%d %H:%M}  {m['home']} – {m['away']}  ({m['league']})")


if __name__ == "__main__":
    main()
