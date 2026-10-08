# Reliability and resource-efficiency review

[Documentation index](README.md) · [Project README](../README.md) · [Agent instructions](../AGENTS.md) · [Development guide](DEVELOPMENT.md)

Reviewed on October 8, 2026 against the current source tree and official documentation. The audit covers framework checks, learning/readiness, browser actions and browser-runtime ownership. Consumer Salesforce business cases remain project inputs.

## Contents

- [Research and design decisions](#research-and-design-decisions)
- [Implemented findings](#implemented-findings)
- [Verification and efficiency boundaries](#verification-and-efficiency-boundaries)

## Research and design decisions

[Playwright assertions](https://playwright.dev/java/docs/test-assertions) retry observations until a caller's expected state appears. A click completing does not establish that asynchronous application state has committed. The custom multi-select now uses a bounded `hasAttribute` assertion after each single selection click.

[Playwright actionability](https://playwright.dev/java/docs/actionability) waits for an actionable element and fails when its operation timeout expires. The engine now caps operation timeouts by both the caller's deadline and the attempt's remaining budget. This fixes calls made with a longer independent deadline; it is not a watchdog for arbitrary Java code or unbounded browser evaluation.

[Playwright isolation](https://playwright.dev/java/docs/browser-contexts) provides independent contexts inside a reused browser. The implementation already follows that architecture; failed context construction now explicitly releases the partially created context while retaining the worker's browser.

[Playwright threading](https://playwright.dev/java/docs/multithreading) requires owner-thread use and describes message-loop-aware waits. [JUnit parallel execution](https://docs.junit.org/6.1.3/writing-tests/parallel-execution.html) requires explicit configuration and synchronization of shared resources. Existing dataset workers each own their runtime; dependency-aware Java cases remain coordinated on one thread. Enabling global Jupiter concurrency would not be a safe speed improvement here.

[Maven Failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/) separates integration execution from verdict verification. Existing Surefire unit/component checks and Failsafe real-browser harnesses already follow this structure; full acceptance uses `verify`.

## Implemented findings

| Finding | Previous behavior | Current behavior | Regression evidence |
| --- | --- | --- | --- |
| Readiness ignored business state | Identical targets/attributes counted as stable while text, value, checked/disabled state or route changed | Stability includes the route and complete semantic state, excluding ephemeral node IDs and capture timestamps | `UiReadinessTest`: five independently changing states each require eight reads; unchanged state with new IDs requires four |
| Multi-select checked state immediately | A delayed `aria-selected` update failed immediately after a successful click | Read-only state assertions wait within the remaining action/attempt budget; each selection click is dispatched once | `ReliabilityHarnessIT`: delayed selection passes on Chromium, Firefox and WebKit; an unchanged state fails with one click |
| Multi-select validated some options after mutation | A duplicate unrequested label was rejected only after requested selections had changed | Validate all visible labels, explicit selection state and declared set size before selection mutation | Duplicate unselected options fail without any selection click |
| Retired runtime could remove a replacement | Calling `close()` twice accessed a closed connection and could clear the current worker reference | Runtime closure is idempotent, validates ownership and requires its own active scope | Closing an old runtime preserves the replacement; closure with an active context fails and leaves that context usable |
| Failed borrowed context leaked | A post-context initialization failure retained one browser context in the shared worker | Close the partially initialized context on failure; only an owned standalone Playwright runtime is closed | Unsupported Firefox CDP initialization leaves zero contexts; a subsequent supported lease opens/closes normally |
| Longer caller deadline bypassed attempt cap | Action timeout used only the caller deadline and configured action limit | Use the minimum remaining caller/attempt budget and action cap | A blocked click with a two-second attempt and a thirty-second caller deadline times out in under five seconds |

Readiness is a discovery gate, not proof of a business outcome. Existing explicit recipe assertions still establish the caller's expected result. A continuously changing scope can now exhaust readiness rather than being falsely accepted; choose the relevant named scope when the page includes live content.

The review adds six credential-free readiness checks and nine real-browser checks. Before correction, all five changing-state readiness probes failed, delayed multi-select failed, duplicate options changed selection before rejection, repeated runtime closure accessed a closed connection, and the failed Firefox lease retained one context. Baseline logs: `.tools/reliability-before.log` and `.tools/reliability-browser-before.log`. The first focused corrected run passed all ten then-present checks (`.tools/reliability-focused.log`); the final full run includes the additional three-engine, negative-state, deadline and active-lease checks.

## Verification and efficiency boundaries

Run with JDK 21 or newer, Node on `PATH` and the installed Playwright browsers:

```powershell
.\mvnw.cmd -B -ntp verify -Pchecks
node --test scripts/model-output-limit.test.mjs scripts/component-adapter.test.mjs scripts/collect-changes.test.mjs
node scripts/check-project-language.mjs
```

Final results are recorded in [VERIFICATION.md](VERIFICATION.md). The checks use local HTML/HTTP fixtures, real browsers and synthetic model transport; they do not require org/model credentials.

The resource improvement has direct evidence: a failed borrowed lease retains zero contexts instead of one. Waiting now follows actual state and remaining budgets, without replaying mutations. No production throughput percentage or paid-model savings were measured in this audit. Existing measurements and separate large-map/reporting priorities remain in [EFFICIENCY.md](EFFICIENCY.md).
