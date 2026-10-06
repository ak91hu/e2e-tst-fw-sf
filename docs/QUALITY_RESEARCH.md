# Quality-focused automation design audit

Reviewed on October 6, 2026 against primary documentation. These decisions extend the existing Java/Salesforce framework. They do not introduce customer business cases, org assumptions or an AI dependency during regression execution.

## Sources and adopted practices

| Primary source | Relevant guidance | Implementation and evidence |
| --- | --- | --- |
| [OpenAI cost guidance](https://developers.openai.com/api/docs/guides/cost-optimization), [Chat Completions reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create), [evaluation guidance](https://developers.openai.com/api/docs/guides/evaluation-best-practices) | Reduce requests/tokens, cap output, measure task quality and cost | Default cost/quality workflow, fresh-target reuse, shared budgets and provider accounting; bounded explicit escalation; internal same-case browser comparison and wrong-outcome rejection; [AI workflow guide](AI_WORKFLOW.md) |
| [Playwright testing practices](https://playwright.dev/docs/best-practices) | Verify user-visible behavior, use semantic locators and asynchronous assertions, isolate runs | Existing locator/context design; new disabled/count/absence assertions; `QualityAssertionsTest`, `QualityHarnessIT`, `QualityCaseWorkflowHarnessIT` |
| [Playwright Java page events](https://playwright.dev/java/docs/api/class-page) | Observe page errors and console events; a failed request differs from an HTTP error response | `PlaywrightEngine` collects bounded/redacted issues; `BrowserQuality` fails enabled unwaived signals; real Chromium crash/console/HTTP/transport/overflow injection |
| [Playwright Java context events](https://playwright.dev/java/docs/api/class-browsercontext) | Context-level page/response/request events permit observation across pages | Monitoring for pages/popups and application-origin requests after authentication; per-persona windows; browser diagnostics in Allure |
| [Playwright Java event dispatch](https://playwright.dev/java/docs/multithreading#synchronous-api-and-event-dispatching) | Synchronous browser calls dispatch events while running the message loop | Owner-thread execution; final quality gates follow outcome captures; completion/failure-capture fault checks |
| [Playwright Java assertions](https://playwright.dev/java/docs/test-assertions) | Retry read-only checks within a deadline rather than a business action | Existing exact expectations plus state/count/absence and `sf.expect`; no automatic action rerun after dispatch |
| [Selenium isolation guidance](https://www.selenium.dev/documentation/test_practices/encouraged/avoid_sharing_state/) | Isolate browser state and avoid shared test data | Fresh browser per suite dataset, explicit private params/personas, permanent attempt journals; preflight before login |
| [Selenium dependency guidance](https://www.selenium.dev/documentation/test_practices/discouraged/test_dependency/) | Prefer independent cases over accidental execution order | Suites continue independent rows after failures; fail-fast retains skipped outcomes; existing explicit JUnit setup dependencies remain validated rather than relying on order |
| [JUnit parameterized-test guidance](https://docs.junit.org/6.1.3/writing-tests/parameterized-classes-and-tests.html) | Exercise the same behavior against multiple inputs with distinct invocations | File-based dataset manifests reuse recipes with separate params and identifiable Allure rows; no dependency migration or unsupported Jupiter invocation semantics |
| [Playwright Java trace viewer](https://playwright.dev/java/docs/trace-viewer) | Rich execution artifacts help investigate failures | Allure receives masked PNGs, redacted event logs, browser diagnostics and quality summaries; raw trace collection is deliberately excluded from defaults because it would bypass the established secret/PII masking boundary |
| [GitHub workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) | Typed dispatch inputs and workflow artifacts support explicit CI selection/evidence | Manual JUnit/suite dispatch, checked required selection, preserved failure artifacts and an English/link validation step |

## Defect-detection requirements and proof

| Requirement | Authoritative implementation | Checks proving the behavior |
| --- | --- | --- |
| A visible successful UI state cannot hide browser/server failures | `BrowserQuality`, `PlaywrightEngine`, CLI/JUnit completion gates | `BrowserQualityTest`, `QualityHarnessIT`, `BrowserQualityLifecycleTest` |
| Browser events delivered during outcome capture cannot escape the final verdict | JUnit final gate follows completion/original-failure capture; new defect failures retain their own failure PNG | Capture-time fault injection first reproduces a false passing result/missing secondary diagnostic, then proves rejection and original-assertion preservation |
| Error-volume limits cannot silently drop evidence and pass | Overflow issue is always fatal | Chromium generates more errors than the configured cap |
| Exceptions cannot become broad permanent suppressions | Exact kind/path/message, reason, future expiration; waived events retained | Expired/broad waiver rejection and mismatched-issue checks |
| Self-healing cannot weaken the oracle | Read-only assertions, no count healing, anchored absence, optional drift gates | Renamed/role/frame drift does not produce fake absence; wrong counts/state fail without dispatch |
| A verification needs actual expected fields | Nonempty `UiVerification` proof and explicit observed fields | Empty expectations/missing actual fields rejected |
| No empty suite or silently invalid selected input can pass | Complete preflight and selection checks | Invalid second dataset prevents any execution/report reservation; empty/unknown selection fails |
| Failed datasets remain failures while independent rows continue | `SuiteCommands`, aggregate status checks | Continued execution, fail-fast, cancellation, post-UI evidence failure and tamper rejection |
| A dataset runs without AI and has its own browser/evidence | Recipe replay uses `CaseRunner`, not a model; each session creates a fresh engine | Real learn/fresh-provider-free replay harness plus isolated dataset command/real-org evidence recorded in verification |
| Reports are actionable and verifiable | Quality/event attachments, owned PNGs, suite child checksums, aggregate Allure | Allure rendering/rebuild/integrity checks; failed gate/incomplete capture rejects a passing report |
| A previous passing dataset cannot substitute for the current execution | Suite-to-row run/case identity, replay mode and zero-model counter validation | Foreign-run, wrong-case and hidden-model report mutations all fail aggregate verification, even when the child evidence passes its own ownership check |
| Public framework text is English and references resolve | Human-reviewed docs/messages plus `check-project-language.mjs` in CI | Scope/count/output retained in `.tools/quality-language-check.json` |

The checks inject specific faults into synthetic applications and assert rejection. They are framework implementation harnesses, not shipped Salesforce business scenarios. Actual application coverage requires the engineer's risk-based cases and independently defined expectations.

## Boundaries and engineering decisions

- Browser/network monitoring covers the finite authenticated execution window and approved application origins. It cannot certify future background activity or unobserved service behavior.
- HTTP 200 and a passed presence check do not prove business correctness. Expected denials/validation must have explicit outcome assertions; a browser waiver only records the reviewed exception.
- Absence requires stable app metadata and complete coverage. The framework rejects an unprovable negative instead of guessing a selector for an inaccessible target.
- Browser isolation does not roll back server-side data. Fixture setup, unique data and explicit ownership remain project responsibilities; permanent retention is preserved.
- Whole-case automatic retries, fabricated expected values and broad silent error ignores would reduce defect sensitivity and are not used.
- Chromium is the configured engine. Accessibility, security, API contract, load and other-browser coverage should be planned as separate checks where the product risk requires them. These are not claimed as implemented browser-suite capabilities.
- Raw unmasked trace/HAR/video exports are not enabled. The implemented default evidence path preserves the existing masking boundary and supplies failure diagnostics without publishing request bodies or credentials.

See the [README engineering guide](../README.md#automation-engineering-workflow), [case workflows](CASE_WORKFLOWS.md), [Allure reporting](ALLURE_REPORTING.md), and [verification](VERIFICATION.md).
