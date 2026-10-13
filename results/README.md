# Results & evidence

This directory holds the machine-generated evidence behind `BENCHMARKS.md`.

## Evidence policy

- Every number published in `BENCHMARKS.md` comes from a file in this
  directory: `steady.json`, `ramp.json`, `connections.json`, `overhead.json`,
  `burst.json` (written by the k6 `handleSummary` hooks in `loadtest/*.js`)
  and `machine.json` (written by `scripts/run_load.ps1`).
- `scripts/generate_benchmarks.py` is the only tool that writes
  `BENCHMARKS.md`. It never invents values: a run without a result file is
  reported as **NOT RUN**, never as zero or an estimate.
- Do not hand-type benchmark numbers into documentation. Regenerate instead:
  `python scripts/generate_benchmarks.py`.
- `machine.json` records the machine (OS, CPU, cores, RAM) the runs executed
  on, so results are reproducible context. If it is missing, the generated
  report says so explicitly.
- Raw k6 summaries are kept as-is; the generator only reads from them.

## Files

| File | Producer | Content |
|---|---|---|
| `steady.json` | `k6 run loadtest/steady.js` | 5000 rps sustained-run summary |
| `ramp.json` | `k6 run loadtest/ramp.js` | 500 -> 20000 rps ramp summary |
| `connections.json` | `k6 run loadtest/connections.js` | high-connection-count run summary |
| `overhead.json` | `k6 run loadtest/overhead.js` | direct vs via-gateway p50/p95/p99 and deltas |
| `burst.json` | `k6 run loadtest/burst.js` | free-tier burst / 429 header validation |
| `machine.json` | `scripts/run_load.ps1` | OS, CPU, cores, RAM of the run machine |

Note: k6 scripts write these paths relative to the current working directory,
so run them from the repository root.