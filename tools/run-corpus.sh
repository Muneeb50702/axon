#!/usr/bin/env bash
#
# AXON-Bench on device — §14.1 corpus, §14.2 metrics (E8).
#
# WHY THIS IS A HOST SCRIPT AND NOT A DEVICE HARNESS
#
# E6b established that `system_server` SIGKILLs the app in batches while it
# holds ~1 GB of model weights, and that whether a given run survives is a
# matter of sweep overlap rather than duration. A driver living inside the app
# process cannot report the run that killed it. So the corpus is driven from
# here, one task per gateway invocation, and every result is appended to disk
# the moment it is known — a kill costs at most the task in flight.
#
# ORACLES ARE EVALUATED EXTERNALLY, ON PURPOSE
#
# Success is decided from `dumpsys` and `uiautomator dump`, not from the trace
# AXON wrote. The agent's own outcome is self-assessment, which is precisely
# what C2 exists to replace; scoring the benchmark with it would let the system
# grade its own homework. The semantics mirror PostConditionEvaluator exactly
# (substring for node_present/absent, case-insensitive regex for text_matches,
# equality for app_foreground) so "succeeded" means the same thing on both
# sides — independent evidence, not a different definition.
#
# RESUMABLE
#
# A task already present in the results file is skipped, so re-running after a
# kill continues rather than restarting.
set -uo pipefail

PKG=dev.axon.android
SVC="$PKG/dev.axon.android.app.gateway.AxonGatewayService"
CORPUS="${CORPUS:-bench/build/axon-corpus.json}"
CONFIG="${CONFIG:-D}"
TIER="${TIER:-core}"
ONLY="${ONLY:-}"
# The agent's own step budget is 6, and a planning step costs ~75 s on this
# hardware (E2), so a task that legitimately uses its whole budget needs ~450 s.
# A shorter host timeout would record "TIMEOUT" for a run that was still working
# and turn a measurement of the agent into a measurement of this script.
TIMEOUT_S="${TIMEOUT_S:-660}"
OUT="${OUT:-bench/results/E8-corpus-${CONFIG}.csv}"

# One runner at a time. Two concurrent passes drive the same phone and the same
# results file, interleaving `logcat -c`, HOME presses and gateway invocations
# -- which is exactly what happened once, producing a run that appeared to stall
# for twenty minutes because each process kept clearing the log the other was
# waiting on. A benchmark that can be started twice by accident will be.
LOCK="${LOCK:-/tmp/axon-corpus.lock}"
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "another corpus run holds $LOCK -- refusing to start a second." >&2
  echo "if that is stale: rmdir $LOCK" >&2
  exit 3
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT INT TERM

mkdir -p "$(dirname "$OUT")"
[ -f "$OUT" ] || echo "task,config,attempted_at,outcome,oracle_pass,wall_ms,agent_ms,llm_calls,steps,killed,detail" > "$OUT"

have_result() { cut -d, -f1 "$OUT" | grep -qx "$1"; }

# --- external oracle ---------------------------------------------------------
dump_ui() {
  adb shell uiautomator dump /sdcard/axon-bench.xml >/dev/null 2>&1
  adb shell cat /sdcard/axon-bench.xml 2>/dev/null
}
foreground_pkg() {
  adb shell dumpsys activity activities 2>/dev/null \
    | grep -m1 topResumedActivity | sed -E 's/.* ([a-zA-Z0-9_.]+)\/.*/\1/'
}

# check_oracle <type> <value> <ui-xml-file> <fg-package>
check_oracle() {
  local type="$1" value="$2" xml="$3" fg="$4"
  case "$type" in
    app_foreground)
      [ "${fg,,}" = "${value,,}" ] ;;
    node_present)
      python3 - "$xml" "$value" <<'PY'
import sys,re
xml=open(sys.argv[1],encoding='utf-8',errors='replace').read(); v=sys.argv[2].lower()
vals=re.findall(r'(?:text|content-desc)="([^"]*)"',xml)
sys.exit(0 if any(v in s.lower() for s in vals) else 1)
PY
      ;;
    node_absent)
      python3 - "$xml" "$value" <<'PY'
import sys,re
xml=open(sys.argv[1],encoding='utf-8',errors='replace').read(); v=sys.argv[2].lower()
vals=re.findall(r'(?:text|content-desc)="([^"]*)"',xml)
sys.exit(1 if any(v in s.lower() for s in vals) else 0)
PY
      ;;
    text_matches)
      python3 - "$xml" "$value" <<'PY'
import sys,re
xml=open(sys.argv[1],encoding='utf-8',errors='replace').read(); p=sys.argv[2]
vals=re.findall(r'(?:text|content-desc)="([^"]*)"',xml)
try: rx=re.compile(p,re.I)
except re.error: sys.exit(0 if any(p.lower() in s.lower() for s in vals) else 1)
sys.exit(0 if any(rx.search(s) for s in vals) else 1)
PY
      ;;
    *) return 1 ;;
  esac
}

# --- driver ------------------------------------------------------------------
# reset_device [start-package]
#
# Every task starts from the launcher unless the corpus says otherwise.
# `start_package` is how §14.1's robustness tier expresses "same task, different
# starting screen"; ignoring it would run those variants from the launcher --
# the same condition as the unperturbed task -- and score them as though a
# perturbation had been applied.
reset_device() {
  local start="${1:-}"
  adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  sleep 2
  if [ -n "$start" ] && [ "$start" != "null" ]; then
    adb shell monkey -p "$start" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 4
    local fg; fg=$(foreground_pkg)
    if [ "$fg" != "$start" ]; then
      echo "    WARNING: wanted $start in front, got $fg -- perturbation not applied" >&2
    fi
  fi
}

run_task() {
  local id="$1" goal="$2" oracle_json="$3" start="${4:-}"

  reset_device "$start"
  adb logcat -c >/dev/null 2>&1
  local t0 killed=0 outcome="NONE"
  t0=$(date +%s%3N)

  adb shell "am start-foreground-service -n $SVC -a dev.axon.action.RUN \
    --es goal '$goal' --es config $CONFIG${MODEL:+ --es model $MODEL}" </dev/null >/dev/null 2>&1

  # The gateway refuses a second concurrent task ("a task is already running"),
  # and silently -- from the caller's side an ignored request looks identical to
  # an accepted one. Without this check the timer would measure the tail of a
  # PREVIOUS task and report it as this one's: that is exactly what produced a
  # 31 s wall clock for a run the agent itself measured at 454 s.
  local started=0 t_wait
  t_wait=$(date +%s%3N)
  while [ $(( $(date +%s%3N) - t_wait )) -lt 20000 ]; do
    if adb logcat -d </dev/null 2>/dev/null | grep -q "AxonAgent: run '"; then started=1; break; fi
    if adb logcat -d </dev/null 2>/dev/null | grep -q "already running; ignoring"; then break; fi
    sleep 2
  done
  if [ "$started" != 1 ]; then
    echo "    REFUSED: the gateway did not start '$id' (busy or crashed); recorded, not timed" >&2
    echo "$id,$CONFIG,$(date -Iseconds),NOT_STARTED,,,,,0,\"gateway did not accept the request\"" >> "$OUT"
    return
  fi

  local deadline=$(( t0 + TIMEOUT_S * 1000 ))
  while :; do
    # TASK_END, not the trace write. A clean replay records NO trace (E24b),
    # so watching for one makes every successful replay look like a hang.
    if adb logcat -d 2>/dev/null | grep -q "AxonGateway.*TASK_END"; then break; fi
    if ! adb shell pidof "$PKG" >/dev/null 2>&1; then killed=1; break; fi
    [ "$(date +%s%3N)" -gt "$deadline" ] && { outcome="TIMEOUT"; break; }
    sleep 5
  done
  local wall=$(( $(date +%s%3N) - t0 ))

  # Let the UI settle before asking what state the device is in.
  sleep 3
  local xml=/tmp/axon-bench-ui.xml
  dump_ui > "$xml"
  local fg; fg=$(foreground_pkg)

  local pass=1 detail=""
  # FD 4: the outer task loop owns FD 3, and nesting the same descriptor
  # would have this loop consume the task list.
  while IFS=$'\t' read -r -u 4 otype ovalue; do
    [ -z "$otype" ] && continue
    if check_oracle "$otype" "$ovalue" "$xml" "$fg"; then
      detail="${detail}${otype}:ok;"
    else
      detail="${detail}${otype}:FAIL;"
      pass=0
    fi
  done 4< <(python3 -c "
import json,sys
for o in json.loads(sys.argv[1]): print(o['type']+'\t'+o['value'])
" "$oracle_json")

  # AXON's own numbers, for context only -- never used to decide pass/fail.
  # Taken from the TASK_END marker, which is emitted on every path including a
  # clean replay; the database only has a row when the task was PLANNED.
  local llm=0 steps=0 agent_ms=0 endline
  endline=$(adb logcat -d 2>/dev/null | grep "AxonGateway.*TASK_END" | tail -1)
  if [ -n "$endline" ]; then
    outcome=$(echo "$endline" | sed -nE 's/.*outcome=([A-Z_]+).*/\1/p')
    llm=$(echo "$endline" | sed -nE 's/.*llm=([0-9]+).*/\1/p')
    steps=$(echo "$endline" | sed -nE 's/.*steps=([0-9]+).*/\1/p')
    agent_ms=$(echo "$endline" | sed -nE 's/.*ms=([0-9]+).*/\1/p')
    [ -z "$llm" ] && llm=0
    [ -z "$steps" ] && steps=0
    [ -z "$agent_ms" ] && agent_ms=0
  fi
  local _unused
  [ "$killed" = 1 ] && outcome="PROCESS_KILLED"

  echo "$id,$CONFIG,$(date -Iseconds),$outcome,$pass,$wall,$agent_ms,$llm,$steps,$killed,\"$detail\"" >> "$OUT"
  printf '  %-24s oracle=%s  outcome=%-16s %6sms  llm=%s\n' "$id" "$pass" "$outcome" "$wall" "$llm"
}

# --- main --------------------------------------------------------------------
echo "AXON-Bench: config=$CONFIG tier=$TIER -> $OUT"
# Read on FD 3, not stdin.
#
# `adb` consumes stdin, so with the task list on stdin the first invocation
# inside the loop swallowed every remaining line and the pass ended after
# exactly one task -- reporting success, having silently skipped eight. It cost
# three confusing restarts to find, because "loop ran once then exited cleanly"
# looks like a finished run, not a bug.
while IFS=$'\t' read -r -u 3 id goal oracle conf start; do
  [ -n "$ONLY" ] && [[ ",$ONLY," != *",$id,"* ]] && continue
  if have_result "$id"; then echo "  $id (already recorded, skipping)"; continue; fi
  if [ "$conf" = "True" ]; then
    echo "  $id -- requires confirmation (§16); not runnable unattended, recorded as GATED"
    echo "$id,$CONFIG,$(date -Iseconds),GATED_CONFIRMATION,,,,,,0,\"irreversible action; §16 requires the user to approve\"" >> "$OUT"
    continue
  fi
  run_task "$id" "$goal" "$oracle" "$start"
done 3< <(python3 -c "
import json,sys
tier=sys.argv[2]
for t in json.load(open(sys.argv[1])):
    if tier!='all' and t['tier']!=tier: continue
    ic = t.get('initial_condition') or {}
    print('\t'.join([t['id'], t['goal'], json.dumps(t['success_oracle']),
                     str(t.get('requires_confirmation',False)), ic.get('start_package') or '']))
" "$CORPUS" "$TIER")

echo
echo "done -> $OUT"
