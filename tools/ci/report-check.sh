#!/usr/bin/env bash
# Validates the server's result report (plugins/AWRE/reports/awre-report.jsonl, written by AwreReport): every line must be one JSON object with
# schema 1 and the sections the format promises. Usage: report-check.sh <file> [--min-lines N] [--last-reason shutdown|timer|command] [--require-activity]
# --require-activity: at least one line must show a player with tilesSent > 0 (the report records real streaming, not just a heartbeat).
# Exit 0 PASS, 1 FAIL (the file is wrong), 2 usage / unreadable. Needs python3 (present on GitHub's ubuntu runners).
set -u
FILE="${1:-}"; [ -n "$FILE" ] || { echo "REPORT-CHECK ERROR usage: report-check.sh <file> [--min-lines N] [--last-reason R]"; exit 2; }
shift; MIN=1; LAST=""; ACT=0
while [ $# -gt 0 ]; do
  case "$1" in --min-lines) MIN="${2:-}"; shift 2 ;; --last-reason) LAST="${2:-}"; shift 2 ;; --require-activity) ACT=1; shift ;; *) echo "REPORT-CHECK ERROR unknown option $1"; exit 2 ;; esac
done
[ -r "$FILE" ] || { echo "REPORT-CHECK ERROR cannot read $FILE"; exit 2; }
command -v python3 >/dev/null || { echo "REPORT-CHECK ERROR python3 not found"; exit 2; }
python3 - "$FILE" "$MIN" "$LAST" "$ACT" <<'PY'
import json, sys, datetime
path, minlines, last, act = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4] == "1"
def fail(msg): print("REPORT-CHECK FAIL: " + msg); sys.exit(1)
raw = open(path, "rb").read()
try: text = raw.decode("utf-8")
except UnicodeDecodeError as e: fail("not valid UTF-8: %s" % e)
lines = [l for l in text.split("\n") if l != ""]
if len(lines) < minlines: fail("%d line(s), expected at least %d" % (len(lines), minlines))
need = {"schema": int, "time": str, "reason": str, "server": str, "java": str, "config": dict, "counters": dict, "players": list,
        "playersOnline": int, "awreSessions": int, "heapUsedMiB": int, "heapMaxMiB": int}
reasons = {"timer", "command", "shutdown"}
for i, l in enumerate(lines, 1):
    try: d = json.loads(l)
    except ValueError as e: fail("line %d is not valid JSON (%s)" % (i, e))
    if not isinstance(d, dict): fail("line %d is not a JSON object" % i)
    if d.get("schema") != 1: fail("line %d: schema is %r, this checker understands 1" % (i, d.get("schema")))
    for k, t in need.items():
        if k not in d: fail("line %d: missing '%s'" % (i, k))
        if not isinstance(d[k], t) or isinstance(d[k], bool): fail("line %d: '%s' should be %s, is %s" % (i, k, t.__name__, type(d[k]).__name__))
    if d["reason"] not in reasons: fail("line %d: reason %r not in %s" % (i, d["reason"], sorted(reasons)))
    try: datetime.datetime.fromisoformat(d["time"].replace("Z", "+00:00"))
    except ValueError: fail("line %d: time %r is not ISO-8601" % (i, d["time"]))
    if not isinstance(d["config"].get("streamEnabled"), bool): fail("line %d: config.streamEnabled missing or not a boolean" % i)
    if not all(isinstance(v, int) and not isinstance(v, bool) for v in d["counters"].values()): fail("line %d: counters must map names to integers" % i)
    for p in d["players"]:
        if not isinstance(p, dict) or "name" not in p or "tilesSent" not in p: fail("line %d: a player entry lacks name/tilesSent" % i)
if last and json.loads(lines[-1])["reason"] != last: fail("last line has reason %r, expected %r" % (json.loads(lines[-1])["reason"], last))
if act and not any(isinstance(p.get("tilesSent"), int) and p["tilesSent"] > 0 for l in lines for p in json.loads(l)["players"]):
    fail("no line shows a player with tilesSent > 0: the server recorded no streaming activity")
d = json.loads(lines[-1])
print("REPORT-CHECK PASS lines=%d last_reason=%s awreSessions=%d tilesSent_total=%d" % (len(lines), d["reason"], d["awreSessions"], sum(p.get("tilesSent", 0) for p in d["players"])))
PY
