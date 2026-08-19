#!/usr/bin/env bash
#
# Validity audit: is any success oracle already true before the task runs?
#
# An oracle satisfied by the initial state measures nothing. The task "passes"
# with the agent having done nothing, and the failure is quiet in the worst
# possible way: a free success is added to EVERY arm equally, so it inflates
# absolute task-success rate while leaving the comparisons between arms intact.
# Nothing in an ablation table would look wrong.
#
# `open_camera` was exactly this. Its oracle was text_matches("camera") and the
# launcher displays a "Camera" icon, so it passed on the home screen. One of ten
# core tasks, found by running this check rather than by reading the corpus --
# the oracle looks perfectly reasonable in source.
#
# Run this against the real initial state before trusting a corpus run.
set -uo pipefail

CORPUS="${CORPUS:-bench/build/axon-corpus.json}"
TIER="${TIER:-core}"

adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 3
adb shell uiautomator dump /sdcard/axon-audit.xml >/dev/null 2>&1
adb shell cat /sdcard/axon-audit.xml > /tmp/axon-audit.xml 2>/dev/null
FG=$(adb shell dumpsys activity activities 2>/dev/null \
      | grep -m1 topResumedActivity | sed -E 's/.* ([a-zA-Z0-9_.]+)\/.*/\1/')

python3 - /tmp/axon-audit.xml "$FG" "$CORPUS" "$TIER" <<'PY'
import json, re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
fg, corpus, tier = sys.argv[2], sys.argv[3], sys.argv[4]
vals = re.findall(r'(?:text|content-desc)="([^"]*)"', xml)

def holds(t, v):
    if t == 'app_foreground': return fg.lower() == v.lower()
    if t == 'node_present':   return any(v.lower() in s.lower() for s in vals)
    if t == 'node_absent':    return not any(v.lower() in s.lower() for s in vals)
    if t == 'text_matches':
        try: rx = re.compile(v, re.I)
        except re.error: return any(v.lower() in s.lower() for s in vals)
        return any(rx.search(s) for s in vals)
    return False

print(f"initial state: foreground={fg}, {len([v for v in vals if v.strip()])} labels")
print()
bad = []
for t in json.load(open(corpus)):
    if tier != 'all' and t['tier'] != tier: continue
    res = [(o['type'], o['value'], holds(o['type'], o['value'])) for o in t['success_oracle']]
    if all(r[2] for r in res):
        bad.append(t['id'])
        mark = "DEFECTIVE — already true"
    else:
        mark = "ok"
    print(f"  {t['id']:<38}{mark}")
print()
if bad:
    print(f"FAIL: {len(bad)} oracle(s) satisfied by the initial state: {', '.join(bad)}")
    sys.exit(1)
print("PASS: no oracle is satisfied before its task runs.")
PY
