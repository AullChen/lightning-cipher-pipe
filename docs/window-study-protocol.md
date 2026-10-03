# Research question and frozen changing-capacity experiment

Status: protocol frozen before measurement; existing policy v2 is unchanged.

Question: when a receiver temporarily reduces its admissible chunk concurrency and the sender does not know the appropriate window in advance, can existing lightweight window feedback reduce the cost of choosing a fixed configuration, while keeping transfer time close to the best measured fixed window?

## Controlled comparison

- Five strategies: fixed windows F1/F2/F4; policy v2 with initial window 1 (A1) or 4 (A4), range 1–4. Three repetitions, strategy order rotated by repetition: 15 fresh JVMs. No selection of the best adaptive starting point after measurement.
- All strategies use the exact same 256 MiB deterministic mixed input, fixed 256 KiB chunks, fixed ZSTD level 3, actual TLS/HPKE, FileSink force and Finish verification. Thus all successful transfers contain 1024 chunks. Only the window can change; no chunk-size or compression advantage is introduced.
- A receiver admission gate accepts at most 4 concurrent chunk requests during [0,12) seconds, at most 1 during [12,28), and 4 thereafter. Epoch is the sender transfer invocation, before Open. Accepted requests drain when capacity drops; no in-flight work is cancelled. Rejected requests receive authenticated BUSY with Retry-After 1, as in the existing reference handler. Each admitted chunk has an additional 80 ms payload-write delay. The sender receives neither the schedule nor the current capacity.
- This is an explicit simulation of receiver admission capacity, not a claim to model all real disks or networks. The advertised maximum remains 4. Original handler BUSY and injected gate BUSY are recorded separately.
- The workload cannot finish before the recovery boundary even at the ideal gate service rate: (4×12+1×16)/0.08 = 800 chunks < 1024, ignoring every other cost. Transfers therefore see both changes. Late recovery may still be right-censored by EOF; do not extend individual inputs to improve the result.
- Every JVM: 4 processors, heap 128–768 MiB; each peer buffer budget 512 MiB, metadata budget 8 MiB; Limits frame 300000, plaintext 262144, maxChunks 4096, maxInFlight 4. Same code, pipeline, security, budgets, input and trace for all strategies. No concurrent builds/load tests during measurement. One process has a 300-second timeout; keep failures and stop on correctness/resource violations.

## Outcomes fixed in advance

1. Completion time, per-repetition excess time relative to the fastest of F1/F2/F4 (a retrospective reference, not a deployable oracle), and the range of performance across initial windows. Report both A1 and A4, not their minimum.
2. Control response: after 12 s, first sampled physical window ≤1 sustained for ≥2 s; after 28 s, first window ≥3 sustained for ≥2 s. These are response metrics, not new policy thresholds. End the data stage at the first sample whose confirmed bytes equal the input size; do not count the Finish verification tail as sustained adaptation. If no sustained response before that boundary/end of the phase, mark it censored rather than zero. A window already in the requested band is reported separately from a reactive adjustment. Fixed windows do not have an adaptation time.
3. Sample every 250 ms: receiver capacity/active requests, confirmed bytes, physical window, rolling last-64 valid ACK P95, pressure, retries and trial counters. Throughput is confirmed-byte difference divided by actual sample interval; the ACK P95 is not an interval P95 or network RTT. Plot intervals at their midpoint; align all panels to the same monotonic elapsed time. Samples around phase boundaries can mix regimes and are not evidence of instantaneous causal attribution.
4. Additional cost: decision CPU nanoseconds, process CPU time, retries/retried frame bytes, number of started/evaluated/rolled-back/interrupted window trials. Sample instrumentation is identical across strategies; its own cost is not separately isolated.
5. Correctness gates: SHA-256 and byte/chunk count match, leases zero, all 15 outcomes retained. Report incomplete cycles and failures; do not interpret a smaller file or omitted failed run as higher throughput.

One multi-panel figure uses repetition 0, selected before measurements: imposed capacity, actual window, throughput and rolling ACK P95. All repetitions' raw traces accompany the figure. Tables use all three repetitions. Do not select a visually favorable run or claim statistical significance from three repetitions.

## Interpretation and scope

Before measurement, define acceptable time cost as no more than 10% excess completion time versus the best fixed window in each paired repetition, for BOTH adaptive starts in ALL three repetitions. Compare start sensitivity using abs(T1-T4)/min(T1,T4) for adaptive versus fixed endpoints; require the adaptive value to be lower in every repetition. These operational criteria are not a significance test. Passing them would support a further, separately frozen study. Beating only a poorly selected fixed window is insufficient: compare with every fixed configuration and the best fixed reference. A1/A4 sensitivity, slow recovery, repeated failed probes or being slower than all sensible fixed windows are negative evidence. This experiment evaluates the existing v2 with fixed thresholds and pressure signals.
