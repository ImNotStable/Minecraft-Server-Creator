# ADR 0124: Why Claude spends more tokens than Codex, and fewer turns

Status: accepted, 2026-11-27. Decided by Claude; Jeremiah asked why Codex villages used 3 to 5 times fewer tokens than
Claude's (1.5-6.9M against 9-30M) and for Claude to get the same savings, cheaply.

## What the runs show

Per run, from the agent logs and MSC's own tool log (`.msc/tool-calls.jsonl`):

| Run | Fresh input | Cached input re-read | Output | MSC results (text + images) |
|---|---|---|---|---|
| Sonnet 5.5, v3 | 0.16M | 10.5M | 103k | 25k + 12k |
| Opus 5.5, v1 | 0.28M | 20.9M | 155k | 37k + 16k |
| Fable 5.2, v1 | 0.24M | 10.7M | 152k | 33k + 13k |
| GPT-6.1 Sol, v3 | 0.09M | 2.9M | 40k | 42k + 15k |
| GPT-6 Astra, v1 | 0.10M | 1.9M | 25k | 36k + 16k |
| GPT-6.2 Sol, v1 | 0.21M | 6.6M | 38k | 46k + 27k |

MSC's own output is small and about the same for both (40-70k a run). The gap is the client: Claude Code re-reads
its whole conversation on every turn, starting near 45k (system prompt and tool schemas) and growing past 200k, over
about 200 turns; Codex re-reads far less. Cached re-reads are billed at a fraction of fresh input, so the cost gap is
smaller than the token gap, but turns are the lever MSC has.

## Decision

- `world_summary review=true` takes several regions in one call (`region="a; b; c"`), each answered under its own
  heading and recorded as if reviewed alone. A final sweep of ten buildings is one turn instead of ten.
- The build guide says every call re-reads the whole conversation, so check several builds in one call and put a
  build's fixes in one `build_batch`.
- Measured by Sonnet runs on the new core: turns and cached input per village against `v3-sonnet55-a`.

- Sonnet's first run on the new core never used it (99 calls, 0 batched reviews), so the failing-reviews list in build
  results, msc_status and dev_cycle now ends with the batched call itself, ready to copy.

## Results (Sonnet 5.5, same village)

| Core | Runs | Calls | Tokens | Wasted |
|---|---|---|---|---|
| v1, old MSC | 2 | 107, 146 | 8.8M, 12.4M | 13%, 23% |
| v3 to v6 (ADR 0122 and this ADR) | 4 | 123, 92, 99, 87 | 10.8M, 7.1M, 9.5M, 8.5M | 4%, 4%, 8%, 3% |

Waste is down to a quarter; calls about 20% and tokens about 15% lower. With the copyable call in the list, the v6 run
batched its re-checks once. The 3-5x token gap to Codex stays: it is how Claude Code carries its context, not what MSC
sends.

## Not done

- Named reviews (`name=`, `why=`) stay one region per call.
- The client side (Claude Code's tool set, history trimming) is outside MSC; the benchmark harness already turns off
  hooks and skills for Claude runs.
