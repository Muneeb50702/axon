#!/usr/bin/env python3
"""Fold Phase 1 acceptance RECORD| lines into the §14.3 A-vs-B table.

Aggregation happens on the host rather than on the device because the device
process does not reliably survive to the end of a run — TECNO's Griffin memory
manager SIGKILLs it after ~7 minutes of sustained load. Each observation is
emitted to logcat the moment it is made, so the results are assembled from
whatever was recorded rather than from a summary the process might never live to
write.

    RECORD|arm|caseId|valid|latencyMs|prefillMs|decodeMs|grammarMs|
           promptTokens|outputTokens|truncated|thermal
"""

import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

FIELDS = [
    "arm", "case", "valid", "latency_ms", "prefill_ms", "decode_ms",
    "grammar_ms", "prompt_tokens", "output_tokens", "truncated", "thermal",
]


def parse(path: Path):
    rows = []
    for line in path.read_text(errors="replace").splitlines():
        i = line.find("RECORD|")
        if i < 0:
            continue
        parts = line[i:].split("|")[1:]
        if len(parts) < len(FIELDS):
            continue
        row = dict(zip(FIELDS, parts))
        for k in ("latency_ms", "prefill_ms", "decode_ms", "grammar_ms",
                  "prompt_tokens", "output_tokens"):
            try:
                row[k] = int(row[k])
            except ValueError:
                row[k] = 0
        row["valid"] = row["valid"].strip().lower() == "true"
        row["truncated"] = row["truncated"].strip().lower() == "true"
        rows.append(row)
    return rows


def pct(values, p):
    if not values:
        return 0
    s = sorted(values)
    return s[min(int(len(s) * p), len(s) - 1)]


def summarise(rows):
    if not rows:
        return None
    lat = [r["latency_ms"] for r in rows]
    return {
        "n": len(rows),
        "valid": sum(r["valid"] for r in rows),
        "valid_rate": sum(r["valid"] for r in rows) / len(rows),
        "median_latency_ms": pct(lat, 0.5),
        "p90_latency_ms": pct(lat, 0.9),
        "median_prefill_ms": pct([r["prefill_ms"] for r in rows], 0.5),
        "median_decode_ms": pct([r["decode_ms"] for r in rows], 0.5),
        "median_grammar_ms": pct([r["grammar_ms"] for r in rows], 0.5),
        "mean_prompt_tokens": round(statistics.mean(r["prompt_tokens"] for r in rows), 1),
        "mean_output_tokens": round(statistics.mean(r["output_tokens"] for r in rows), 1),
        "truncated": sum(r["truncated"] for r in rows),
    }


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: aggregate-acceptance.py <acceptance-raw.log>", file=sys.stderr)
        return 2

    path = Path(sys.argv[1])
    rows = parse(path)
    if not rows:
        print(f"no RECORD| lines in {path}", file=sys.stderr)
        return 1

    by_arm = defaultdict(list)
    for r in rows:
        by_arm[r["arm"]].append(r)

    arms = {a: summarise(rs) for a, rs in by_arm.items()}
    a = arms.get("A_naive")
    b = arms.get("B_grammar")

    def cell(s, key, fmt="{}"):
        return "—" if s is None else fmt.format(s[key])

    print("=" * 62)
    print("AXON Phase 1 acceptance — §13 criterion / §14.3 ablation rows A-B")
    print("=" * 62)
    print(f"{'metric':<24}{'A (naive)':>18}{'B (grammar)':>18}")
    print("-" * 62)
    print(f"{'cases':<24}{cell(a,'n'):>18}{cell(b,'n'):>18}")
    print(f"{'valid actions':<24}{cell(a,'valid'):>18}{cell(b,'valid'):>18}")
    print(f"{'valid-action rate':<24}"
          f"{('—' if not a else f'{a['valid_rate']*100:.1f}%'):>18}"
          f"{('—' if not b else f'{b['valid_rate']*100:.1f}%'):>18}")
    print(f"{'median latency':<24}{cell(a,'median_latency_ms','{} ms'):>18}"
          f"{cell(b,'median_latency_ms','{} ms'):>18}")
    print(f"{'p90 latency':<24}{cell(a,'p90_latency_ms','{} ms'):>18}"
          f"{cell(b,'p90_latency_ms','{} ms'):>18}")
    print(f"{'median prefill':<24}{cell(a,'median_prefill_ms','{} ms'):>18}"
          f"{cell(b,'median_prefill_ms','{} ms'):>18}")
    print(f"{'median decode':<24}{cell(a,'median_decode_ms','{} ms'):>18}"
          f"{cell(b,'median_decode_ms','{} ms'):>18}")
    print(f"{'grammar sampling':<24}{'—':>18}{cell(b,'median_grammar_ms','{} ms'):>18}")
    print(f"{'mean prompt tokens':<24}{cell(a,'mean_prompt_tokens'):>18}"
          f"{cell(b,'mean_prompt_tokens'):>18}")
    print(f"{'mean output tokens':<24}{cell(a,'mean_output_tokens'):>18}"
          f"{cell(b,'mean_output_tokens'):>18}")
    print(f"{'truncated':<24}{cell(a,'truncated'):>18}{cell(b,'truncated'):>18}")
    print("-" * 62)

    if b:
        ok = b["valid_rate"] == 1.0
        print(f"§13 acceptance (100% schema-valid under grammar): "
              f"{'PASS' if ok else 'FAIL'}  [{b['valid']}/{b['n']}]")
    if a and b:
        print(f"§14.3 expectation (B > A on valid-action rate): "
              f"{'PASS' if b['valid_rate'] >= a['valid_rate'] else 'FAIL'}")

    out = path.with_suffix(".json")
    out.write_text(json.dumps({"arms": arms, "n_records": len(rows)}, indent=2))
    print(f"\nwritten: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
