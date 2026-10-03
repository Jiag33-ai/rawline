#!/usr/bin/env bash
# Checks that every model URL is still reachable (HEAD request) and prints its size. The app downloads the files itself on first use.
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PY'
import json, urllib.request
for m in json.load(open("models.json"))["models"]:
    req = urllib.request.Request(m["url"], method="HEAD")
    with urllib.request.urlopen(req, timeout=30) as r:
        print("%-8s HTTP %s  %6.1f MB  %s" % (m["id"], r.status, int(r.headers.get("Content-Length", 0)) / 1e6, m["url"].split("/")[-1]))
PY
