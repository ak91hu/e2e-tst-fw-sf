# Framework efficiency assessment

[Documentation index](README.md) · [Project README](../README.md) · [Agent instructions](../AGENTS.md) · [Development guide](DEVELOPMENT.md)

Assessed on October 6, 2026 against repository source, repeated local measurements and retained verification evidence. The framework is effective at eliminating model usage from repeated recipe execution and at reducing duplicate discovery work. Short-case wall time is dominated by context startup and reporting; large locator repositories have a measurable read-path cost. The evidence supports those specific conclusions, not universal Salesforce compatibility, production throughput or paid-model quality claims.

## Contents

- [Method and reproducibility](#method-and-reproducibility)
- [Measured components](#measured-components)
- [AI cost effectiveness](#ai-cost-effectiveness)
- [Quality retained during measurement](#quality-retained-during-measurement)
- [Bottlenecks and remaining improvement priorities](#bottlenecks-and-remaining-improvement-priorities)
- [Practical assessment](#practical-assessment)

## Method and reproducibility

[MeasureFrameworkEfficiency.java](../scripts/MeasureFrameworkEfficiency.java) runs against the actual packaged Java CLI, Playwright engine, learner, deterministic executor, locator store, AI workflow and bundled Allure renderer. It requires no customer credentials or model endpoint. Run from the repository root with JDK 21 and installed Chromium:

```powershell
java -cp sf-e2e-cli/target/sf-e2e.jar scripts/MeasureFrameworkEfficiency.java 5
node --test scripts/model-output-limit.test.mjs
```

Each independent run excludes one warmup and alternates baseline/reuse learning order. The measured case fills one stable input and asserts the supplied expected value. The provider is scripted, has zero artificial latency and reports a fixed 20 input/5 output tokens per call. Five fresh contexts are used for each learning/replay/healing/restart phase; browser startup has ten measured learning samples. Each replay reloads the repository from disk. Thirty lookups per store size follow an excluded initial lookup. Allure is generated three times against the same synthetic benchmark results using its existing extraction cache. Medians, minima and maxima remain in `efficiency.json`.

The latest script additionally pairs each warm store lookup with the same key read through a newly constructed read-only store (`storeColdLookup10/100/1000`). Both branches read/hash current bounded file bytes; only the warm branch can reuse a validated parsed snapshot. The report renderer now verifies published HTML and removes its current successful temporary output. Historical timings/directories below retain the behavior of the earlier versions.

Two independent runs passed. Raw local artifacts are under `report/2026-10-06_12-37-12.623918600Z` and `report/2026-10-06_12-40-41.219197300Z`. Each directory has metrics, synthetic maps/recipes, masked PNGs and actual Allure HTML. The second report also embeds the pre-render core measurements and one masked fixture image as Allure attachments. These ignored runtime directories are local evidence; a fresh checkout recreates its own timestamped evidence with the script. No business records or target session files were used or modified.

## Measured components

### Content-validated snapshots measured after the capability review

The five-sample run at `report/2026-10-06_13-45-09.123336000Z` passed all learning/replay/healing/restart/wrong-expectation gates and generated three real Allure reports. Its paired store probes performed 30 reads per branch/size against the same files. A cold branch constructs a new read-only store for each lookup; a warm branch uses one previously validated store. Both read and SHA-256-check current bytes.

| Repository size | Cold validation median, ms | Warm validated lookup median, ms | Local ratio |
| --- | ---: | ---: | ---: |
| 10 entries | 0.459 | 0.195 | 2.4x |
| 100 entries | 2.516 | 0.511 | 4.9x |
| 1000 entries | 12.360 | 1.630 | 7.6x |

The 1000-entry repeated lookup is approximately 86.8% shorter in this fixture. This is a store-component comparison, not a 7.6x Salesforce test-run speedup. OS file caching/JVM behavior and repository shape influence results; current-byte I/O/hashing are intentionally retained. Fresh browser startup median was 669 ms and Allure generation median was 2898 ms, so those costs remain important for short cases.

After the three renders, the new run retained exactly the two published HTML copies and no `.allure-render-*` directories. Raw results, five masked replay PNGs, maps/recipes and attached core/cost metrics remained. Older reports and failed-render directories were not deleted. The benchmark verifies publication checksums after rendering. The current CLI is built from the verified snapshot/picklist/cleanup sources; log: `.tools/salesforce-capability-benchmark.log`.

### Earlier component baseline

Times below are milliseconds. Learning/replay core excludes construction/closure of the browser. Total includes those operations; only the fresh replay total includes one measured screenshot. These totals exclude real authentication and the CLI's complete journal/evidence/export pipeline. Allure is a separate component.

| Component | First run median | Second run median | Second run min–max | Samples per run |
| --- | ---: | ---: | ---: | ---: |
| Fresh browser startup | 692.1 | 691.5 | 680.5–722.8 | 10 |
| Learning without target reuse, core | 947.5 | 952.5 | 930.7–988.1 | 5 |
| Learning with target reuse, core | 552.0 | 555.1 | 542.9–572.9 | 5 |
| Learning without reuse, total | 1728.4 | 1729.8 | 1695.9–1792.7 | 5 |
| Learning with reuse, total | 1330.8 | 1321.9 | 1311.2–1383.4 | 5 |
| Provider-free replay, core | 129.1 | 133.2 | 126.1–142.1 | 5 |
| Fresh replay with one PNG, total | 952.7 | 986.4 | 954.4–1042.8 | 5 |
| First label-drift replay, core | 1186.5 | 1178.9 | 1146.2–1195.4 | 5 |
| First label-drift replay, total | 1961.4 | 1991.0 | 1942.3–2035.4 | 5 |
| Fresh strict restart after repair, core | 141.1 | 133.2 | 129.8–140.7 | 5 |
| One masked screenshot | 57.1 | 61.4 | 54.3–118.0 | 5 |
| Allure single-file generation | 3003.1 | 2559.5 | 2537.4–3106.7 | 3 |
| Checked store lookup, 10 entries | 0.537 | 0.554 | 0.448–0.887 | 30 |
| Checked store lookup, 100 entries | 2.624 | 2.631 | 2.178–4.163 | 30 |
| Checked store lookup, 1000 entries | 15.319 | 13.787 | 11.218–20.132 | 30 |

The benchmark has a small sample count, local loopback HTML and warm browser installation/Allure cache. It measures neither production p95 nor an SLA. Windows scheduler, JVM warmup, storage and browser versions influence results. There is no simulated provider delay to manufacture a larger speedup. Real target/provider latency could change the relative costs substantially; their speed and quality need a private target evaluation.

## AI cost effectiveness

### Additional AI optimizations verified on October 6

The default workflow now compacts discovery wire requests, uses adaptive output allowances, groups upcoming visible same-scope case targets and reuses independently verified unchanged mapped scopes. These are implementation changes, not advice to select a cheaper model. Every switch is configurable; see [AI workflow](AI_WORKFLOW.md).

A real local HTTP/Chromium comparison used three distinct field targets followed by the same caller-supplied value assertion. The baseline retained existing same-target reuse but disabled the three new optimization switches. Both branches passed the assertion; fresh saved replay made zero provider calls and rejected a wrong expectation.

| Measurement | Baseline | Optimized |
| --- | ---: | ---: |
| Physical target-discovery calls | 3 | 1 |
| Actual serialized HTTP input bytes, sum | 9655 | 2502 |
| Output caps, summed across physical calls | 12288 | 896 |
| Fresh recipe replay provider calls | 0 | 0 |

This fixture reduces calls by 66.7% and transmitted input by 74.1%. The cap reduction limits possible output and conservative reservation; it is not a measurement of generated tokens. Local HTTP usage was computed synthetically from wire size and fixed output counts, with illustrative rates. No paid provider, invoice saving or universal model-quality improvement is claimed.

Additional fault injection confirms changed UI invalidates a pending batch, invented node IDs fail before locator publication/business actions, an oversized optional batch falls back to complete single-target discovery, and a failed output cap gets at most one full-allowance primary repair. An already full-allowance truncation does not repeat the same paid request. Complete unchanged mapping makes zero additional provider calls and leaves the repository bytes unchanged; changing a control causes fresh mapping.

The public benchmark now records paired three-target `aiCostComparison` samples in both `efficiency.json` and the Allure JSON attachment. It retains the original two-step reuse/replay/healing measurements and adds two extra fixture inputs for the batch comparison. New timings should be compared within their own run, rather than treated as identical to the historical one-input measurements below. Its provider usage is still scripted, while workflow byte metrics represent the serialized payload the default transport would send. `BatchLearningHarnessIT` independently measures actual HTTP request bodies.

The five-pair run at `report/2026-10-06_13-18-51.855335300Z` passed with the same 3-to-1 call result in every pair. Baseline serialized input ranged from 9781 to 9787 bytes; optimized input was 2553 bytes in every pair, approximately 73.9% lower. Output allowances were 12288 versus 896 in every pair. `efficiency.json` retains the snapshots, zero-provider replay/healing results and wrong-expectation rejection; `index.html` contains the real Allure rendering with an attached masked PNG and JSON metrics. This script does not perform paid API calls or load org credentials.

### Earlier same-target reuse baseline

Both runs produced the same physical-call outcome for every pair:

| Mode | Learning calls | Synthetic reported tokens | Later replay/healing/restart calls |
| --- | ---: | ---: | ---: |
| Target reuse disabled | 2 | 50 | 0 |
| Fresh target reuse enabled | 1 | 25 | 0 |

This is a 50% call/token reduction for this fixture. Full learning median fell 23.0% in the first run and 23.6% in the second; core median fell approximately 42% in both. The smaller wall-time benefit reflects browser/startup work and the scripted provider's zero latency. Fixed tokens and illustrative rates are accounting fixtures, not provider bills or a measured token reduction across realistic Salesforce screens.

The primary ongoing saving is provider-free replay: `CaseRunner` has no agent dependency and forces AI fallback off. Its recipe contains routes, stable locator references and runtime parameter references. Subsequent verified execution does not instantiate a provider. Initial learning and later explicit re-learning still have costs; compute, storage, authentication and reporting continue to consume resources even when AI usage is zero.

Conservative reservations limit oversized/unknown usage and avoid repeated unpaid-looking failures. However, serialized bytes plus framing/output allowance can reject a request that a provider tokenizer would admit. Rates are project configuration, monetary ceilings are estimates, and unknown commercial pricing remains unknown. This favors bounded spend over maximum token utilization. Model selection and semantic-intent evaluation are still project responsibilities.

## Quality retained during measurement

Every learning and replay phase verified the caller's value with deterministic code. A real label change triggered one identity-supported repair, which was written to disk. A fresh strict/read-only restart resolved the updated locator with no repair/model. A separate wrong expected value returned `ASSERTION_FAILED`. Fewer calls did not remove those checks.

The earlier full/focused Java evidence contained 190 checks across 32 suites, including 33 Chromium checks. The AI optimization verification reruns the full framework checks and focused new boundary/fault checks; the latest inventory is recorded in [verification](VERIFICATION.md). Three credential-free Node adapter checks also remain required. The internal benchmark is additional measured evidence, not consumer business coverage or a hosted CI claim.

The existing fault harnesses reject wrong counts/states, unprovable absence, duplicate identities, incomplete observations, browser/network defects and tampered evidence. Schema/current-node validity alone cannot establish that an AI chose the correct business control. Engineers must review learned intent/artifacts and evaluate representative real controls, permissions, locales and known defects with fixed expected outcomes. An empty generic template necessarily has no target-app coverage until the consumer supplies cases.

## Bottlenecks and remaining improvement priorities

| Priority | Finding and evidence | Consequence | Suitable next improvement |
| --- | --- | --- | --- |
| High, fixed in this audit | Subscription launcher ignored the Java output allowance and used 16000 | A configured smaller allowance was not forwarded to the adapter | Forward modern/compatibility allowance through a validated helper; three boundary checks now run in CI |
| High for large maps, implemented in the capability review | Earlier `LocatorStore.get` reparsed/checksummed the whole object graph; 1000-entry lookup median 13.8–15.3 ms | Many repeated resolutions accumulated parsing/serialization overhead | Immutable parsed snapshot now reuses identical SHA-256-checked content; every lookup still reads bounded bytes, and changed files are fully revalidated |
| Medium for short cases | Allure launches a JVM for generation; measured 2.6–3.0 seconds | Reporting can dominate very short isolated executions | Prefer existing aggregate suites; evaluate renderer lifecycle only while retaining per-run verdict/evidence ownership |
| Medium for disk usage, implemented in the capability review | Earlier renderer retained `.allure-render-*` after publication | Repeated rebuilding grew storage beyond the two intentional published HTML copies | Current successful render is removed only after both publications verify; existing/failed directories and raw evidence remain intact |
| Medium for large pages | Mapper/learner uniqueness checks repeatedly scan observed nodes; source review shows pairwise work in mapping | Large semantic scopes can add CPU/prompt size and reach observation/budget limits | Profile representative large scopes, then evaluate indexed semantic identities without changing ambiguity rules |
| Medium for latency/throughput, implemented | Fresh contexts remain isolated; scoped runtime reuse and 1..8 resource-aware suite workers are available | Browser startup is amortized; shared identities/resources remain serialized | Use independent personas/data and reviewed resource locks; measure target throughput separately |
| Medium for realistic confidence | Historical benchmark uses one input; the added cost comparison uses three fields and synthetic provider output | Neither can rank provider/model quality or predict a complex org's latency | Maintain a private fixed evaluation set, budgeted real-provider trials and defect rejection measurements |
| Low for distribution | The earlier audit's shaded CLI was 253084453 bytes, approximately 241.4 MiB | Initial downloads/cache restoration are sizeable | Review packaging contents and artifact caching without removing required offline runtime/reporting capabilities |

`UiReadiness.stable` requires four matching observations with three polling intervals. Stability includes route, text, value, checked/disabled state and semantic attributes; ephemeral node IDs and timestamps are excluded. The Playwright polling interval is up to 100 ms. This contributes roughly 300 ms to a stable target-learning readiness phase before observation overhead. Avoiding a second such phase helps explain the local learning speedup even without provider latency. The [October 8 reliability review](RELIABILITY_REVIEW.md) verifies changing-state rejection, asynchronous single-dispatch selection, attempt-budget enforcement and elimination of failed-lease context retention.

Healing tries exact identity before promotion. Three default retry delays of 250 ms are implemented as three 100 ms polls per retry, approximately 900 ms total before additional observation/repair work if the deadline allows it. The first drift penalty near one second is therefore consistent with the code. Persisted repair brings the next strict run back close to ordinary replay. Reducing retries blindly could increase premature healing on a slowly loading app; choose policy from observed target behavior.

The earlier second benchmark's three renders left five HTML files: root publication, `allure-report` publication and three temporary render copies. Each was 2644487 bytes. These copies alone totaled approximately 12.6 MiB, excluding maps/PNGs/results. Those historical outputs remain preserved. The later capability review implements content-validated snapshot reuse and cleanup of newly successful owned render outputs; see [the review](SALESFORCE_CAPABILITY_REVIEW.md) for retained guarantees and remaining development gaps.

## Practical assessment

Page Object Model is now the default for case learning/replay and Java composition. Session-scoped wrapper reuse centralizes page/control access; fresh observations, existing healing checks and independent assertions remain intact. This architectural change has no separate measured throughput percentage. New case IDs/recipe filenames use `TST_`; historical recipes remain compatible. Reviewed change-impact selection, automatic context-separated trend dashboards and a concrete pinned LWC/Jest adapter are also implemented; see [efficiency workflows](EFFICIENCY_WORKFLOWS.md). These features have no separate universal speedup claim.

The development-plan benchmark passed five alternating paired samples in `report/2026-10-06_14-40-58.037709700Z`. Two fresh-process cases took a median **2049.47 ms**; the same two cases in fresh contexts within a scoped reused process took **1201.47 ms** (41.4% shorter). Both branches fill and verify the same expected value, reject shared DOM state and require masked PNG evidence. Browser launch is included once in the pooled branch; actual Salesforce authentication/network, Allure generation and hosted throughput are excluded from this specific comparison. The single-case replay/healing branches still make zero provider calls and reject a wrong expectation. Core metrics and a masked PNG remain embedded in real Allure HTML.

This run also measured 1000-entry warm/cold lookup medians of 2.98/21.89 ms, screenshot median 56.95 ms and Allure render median 3.05 s. These are local component observations, not a production p95 claim. Metadata durations now accompany normal attempts and report rendering; use their phase/ownership rather than adding overlapping totals. Evidence: `.tools/roadmap-benchmark.log` and the timestamped `efficiency.json`.

| Dimension | Assessment |
| --- | --- |
| Repeated AI cost | Strong: saved recipes and deterministic repair use zero provider calls |
| Initial discovery cost | Controlled: fresh target reuse, exact read-only cache and bounded call/token/cost reservations; savings depend on actual repeated targets |
| Small-case core speed | Good in the local fixture; total execution retains browser/reporting overhead |
| Repair behavior | Bounded and persistent when identity proof succeeds; first drift intentionally incurs retries |
| Large-map efficiency | Content-validated snapshot removes repeated parse/serialization work; paired 1000-entry lookup improves locally by 7.6x; broad large-app throughput remains unestablished |
| Defect sensitivity | Meaningful synthetic negative/fault evidence; real business coverage depends on consumer intent/oracles |
| Universal Salesforce coverage | Configuration is generic; hidden/inaccessible/custom UI and target authentication remain explicit boundaries |
| Operational traceability | Strong retained timestamped evidence and offline Allure; storage capacity needs planning |

Use verified recipes for routine regression, aggregate datasets with `run-suite`, preserve screenshot/defect gates, keep model trials budgeted and compare quality alongside cost. Measure representative target flows before making throughput or release-time commitments. See [README](../README.md), [AI workflow](AI_WORKFLOW.md) and [verification](VERIFICATION.md).
