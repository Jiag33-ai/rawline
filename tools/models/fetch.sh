#!/usr/bin/env bash
# Model link checks. The app downloads the files itself on first use; this only checks the links and pinned hashes.
#   fetch.sh --consistency          offline: models.json and Models.kt (the app) list the same URLs and ids
#   fetch.sh                        consistency, then a HEAD request per link (retries), size compared with the app's approxMb
#   fetch.sh --verify-hashes [MB]   as above, then download every pinned pack up to MB (default 60) and compare its SHA-256
#                                   with the one in Models.kt. Bigger packs are only size-checked. Meant for a scheduled job.
set -euo pipefail
cd "$(dirname "$0")"
MODE="${1:-links}"
MAXMB="${2:-60}"
MODE="$MODE" MAXMB="$MAXMB" python3 - <<'PY'
import hashlib, json, os, re, sys, time, urllib.request

mode, maxmb = os.environ["MODE"], int(os.environ["MAXMB"])
src = open("../../core/ml/src/main/kotlin/app/rawline/core/ml/ModelStore.kt").read()
base = re.search(r'const val AI_HUB = "([^"]+)"', src).group(1)
packs = {}
for m in re.finditer(r'val (\w+) = ModelPack\("(\w+)",\s*"[^"]*",\s*(\d+),\s*"([^"]+)"(.*?)\n\s*(?=val |\n|ALL)', src, re.S):
    body = m.group(5)
    h = re.search(r'sha256 = "([0-9a-f]{64})"', body)
    packs[m.group(2)] = {"mb": int(m.group(3)), "url": m.group(4).replace("$AI_HUB", base), "sha256": h.group(1) if h else None}
listed = {m["id"]: m["url"] for m in json.load(open("models.json"))["models"]}

bad = []
if set(packs) != set(listed): bad.append("ids differ: app %s, models.json %s" % (sorted(packs), sorted(listed)))
for i in packs.keys() & listed.keys():
    if packs[i]["url"] != listed[i]: bad.append("%s: url differs between Models.kt and models.json" % i)
    if not packs[i]["url"].startswith("https://"): bad.append("%s: not https" % i)
if len(packs) < 5: bad.append("could not read all packs from Models.kt (found %d)" % len(packs))
for i, p in packs.items():
    if p["sha256"] is None: print("note: %-8s has no pinned hash (unversioned link)" % i)
if bad:
    print("\n".join("FAIL " + b for b in bad)); sys.exit(1)
print("ok   models.json and Models.kt agree on %d packs" % len(packs))
if mode == "--consistency": sys.exit(0)

def req(url, method):
    last = None
    for attempt in range(4):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=60)
        except Exception as e:
            last = e; time.sleep(2 ** attempt)
    raise last

fails = []
for i, p in packs.items():
    with req(p["url"], "HEAD") as r:
        size = int(r.headers.get("Content-Length", 0)); p["size"] = size
        ratio = size / 1e6 / p["mb"]
        ok = r.status == 200 and 0.5 <= ratio <= 1.6   # approxMb is only approximate, but a pack twice or half the size has changed
        print("%s %-8s HTTP %s  %7.1f MB (app says about %d)" % ("ok  " if ok else "FAIL", i, r.status, size / 1e6, p["mb"]))
        if not ok: fails.append("%s: unexpected size or status" % i)
if mode == "--verify-hashes":
    for i, p in packs.items():
        if p["sha256"] is None: continue
        if p["size"] > maxmb * 1e6:
            print("skip %-8s %.0f MB is over %d MB, size checked only" % (i, p["size"] / 1e6, maxmb)); continue
        h = hashlib.sha256()
        with req(p["url"], "GET") as r:
            while True:
                b = r.read(1 << 20)
                if not b: break
                h.update(b)
        ok = h.hexdigest() == p["sha256"]
        print("%s %-8s sha256 %s" % ("ok  " if ok else "FAIL", i, "matches Models.kt" if ok else "DOES NOT match Models.kt (got " + h.hexdigest() + ")"))
        if not ok: fails.append("%s: hash mismatch" % i)
if fails:
    print("\n".join("FAIL " + f for f in fails)); sys.exit(1)
PY
