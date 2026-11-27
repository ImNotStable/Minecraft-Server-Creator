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

## Not done

- Named reviews (`name=`, `why=`) stay one region per call.
- The client side (Claude Code's tool set, history trimming) is outside MSC; the benchmark harness already turns off
  hooks and skills for Claude runs.
