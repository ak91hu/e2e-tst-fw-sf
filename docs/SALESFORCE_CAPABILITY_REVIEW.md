# Salesforce automation capability and performance review

Reviewed on October 6, 2026 against repository code, local measurements and primary Salesforce/Playwright documentation. This is an engineering assessment and acceptance roadmap, not a claim that every Salesforce product or org has been tested. The consumer business-test workspace remains empty.

## Assessment

The framework provides org-independent browser E2E, AI learning, persisted provider-free replay/healing and timestamped Allure. All ten prioritized tracks below now have executable implementations: REST/fixtures, scheduling, file/page/widget recipes, private traces, quality modules, release-layer interoperability and a separate native adapter. [Usage](ADVANCED_CAPABILITIES.md) and the [ledger](ROADMAP_IMPLEMENTATION.md) distinguish shipped contracts from private org/device certification. External Apex/Jest/Appium installations and business coverage remain consumer inputs.

Salesforce explicitly recommends Jest for isolated LWC tests; those tests run without a browser or org. Moving component-only checks to that layer can shorten feedback while keeping critical business journeys in E2E. This is a strategy inference from the [official LWC testing guidance](https://developer.salesforce.com/docs/platform/lwc/guide/testing.html), not a measured speedup for this repository.

## Changes implemented in this review

| Change | Purpose | Preserved quality contract |
| --- | --- | --- |
| Validated locator snapshot | Avoid reparsing/reconstructing/checksumming the entire JSON object graph on every lookup | Read bounded file bytes and compute SHA-256 on every lookup; changed content is fully revalidated, even with identical size and restored modification time |
| Delayed custom picklist options | Wait for options rendered after the opening click | One opening click; exact visible option name, bounded action deadline, ambiguity failure and no automatic business-action repeat |
| Picklist frame/container ownership | Search the observed frame and ARIA-controlled/owned container when supplied | An unrelated same-name option in another container/frame cannot substitute for the owned option |
| Successful Allure temporary output cleanup | Avoid a third large HTML copy accumulating after each successful render/rebuild | Verify both published HTML copies first; remove only the current owned temporary render directory; preserve raw results, PNGs, previous directories and failed renders |
| Paired cold/warm store measurement | Separate validation cost from repeated resolution overhead | Same file, same keys and complete integrity checks; new store instances form the cold branch |

The locator snapshot is per `LocatorStore` instance and immutable. It is not a UI/DOM cache. Every action still resolves fresh UI identity, and external locator edits/deletion/recreation are visible on the next read. File I/O and byte hashing remain; memory holds at most the current bounded repository snapshot per instance. Locked optimistic writes and scope-remap archives retain their existing behavior.

Custom picklist selection uses transient `aria-controls`/`aria-owns` IDs only inside the current action; they are not persisted as locator identity. Without an ownership reference, the fallback requires exactly one matching visible option in the observed frame. Specialized multi-select, closed-shadow option widgets and portal menus outside the owning frame still need representative component evaluation; this change does not establish compatibility for every custom widget.

## Capability matrix

**Built in** means implemented with internal evidence; production adoption still needs private target evaluation. **Partial** preserves the stated boundary. **External layer** uses separate tools through the shipped release-matrix/JUnit interoperability rather than inventing their test coverage.

| Requirement | Status | Current behavior or missing acceptance contract |
| --- | --- | --- |
| Generic standard/custom/managed-package object UI | Partial | Routes/labels/scopes are configured/observed; no packaged org/object workflow; inaccessible/custom widget behavior remains target-dependent |
| Lightning, Classic and Experience-shaped web routes | Partial | Local route/session harnesses exist; no complete live certification for all clouds, editions or Experience templates |
| Open/tracked closed Shadow DOM and approved frames | Partial | Traversal/coverage and frame identity gates exist; untracked roots, inaccessible frames and exceeded limits fail explicitly |
| AI autonomous visible navigation discovery | Built in | Bounded eligible read-only links; hidden business states require supplied intent |
| JSON/text case learning and provider-free replay | Built in | Supplied explicit outcomes are deterministically executed before recipe publication; later replay does not construct a provider |
| Persistent self-healing | Built in | Identity/confidence/margin/ambiguity gates, bounded pre-dispatch retry and persisted revision/history; no expectation rewriting |
| Common text/date/numeric/checkbox/native-select actions | Built in | Semantic targeting and native actionability; application-specific validation/date formatting remains caller-owned |
| Delayed custom single-select picklists | Partial | This review adds bounded option readiness and frame/ARIA ownership; duplicate options fail |
| Lookup/typeahead and dependent picklists | Built in | Exact frame/control-owned lookup results and bounded picklist readiness; dependency/expected choices remain explicit consumer inputs |
| Multi-select, rich text, drag/drop, CPQ grids and virtual scrolling | Partial | Native/ARIA selected-state adapters, contenteditable fill, bounded exact grid-row search and workspace tabs; virtual selected sets/drag-drop/arbitrary CPQ require target adapters |
| Screen Flow, wizard, approval and console business flows | Partial | Explicit supplied steps can exercise observable UI; branch expectations/setup and workspace-tab adapters are not inferred automatically |
| Browser tabs/popups/native confirmation dialogs | Built in | Explicit popup routes/page switching and exact dialog decisions, fresh page/frame identity; unplanned dialogs are dismissed |
| File upload/download and content verification | Built in | Opt-in input root, private owned outputs, 64 MiB bounds, exact checksum/optional content expectations and redacted transfer metadata |
| JWT and external storage-state sessions | Built in | Validated destinations, external credentials, origin checks and session-secret masking |
| Enterprise SSO/MFA provisioning and automatic renewal | Partial | External authorized state can be used; provisioning/renewal belongs to the installation; no MFA bypass or interactive identity-provider automation is bundled |
| Multiple personas, permissions, sharing/FLS negatives | Partial | Separate identities/namespaces and supplied negative outcomes; no automatic permission fixture/provisioner or full security coverage |
| Locale/timezone/currency-dependent UI | Built in | Configurable browser settings and explicit value parsers; target labels and expected values are consumer data |
| Test-data ownership and provenance | Built in | Metadata-aware one-shot creates, owned parents, sequential per-record batch outcomes, uncertain-commit reconciliation and permanent journals; no automatic deletion |
| Independent persisted-result verification | Built in | Exact REST ID/fields, paginated SOQL, bounded eventual read-only assertions and explicit version/authorization/API accounting |
| API/integration contracts and platform-event verification | Partial | REST/schema/usage gates and external API result layers; Composite/Bulk/PubSub transports are outside this implementation |
| Browser/console/HTTP/transport quality gates | Built in | Bounded redacted signals, exact expiring waivers, preserved failure diagnostics and final verdict gates |
| Allure/default masked screenshots/report integrity | Built in | Automatic real renderer, UTC run directories, raw evidence/ownership/checksums, offline rebuild/verify and successful temporary cleanup |
| Trace/video/HAR and structured failure diagnosis | Partial | Authenticated failure-only private tracing, ACL/ownership/size/expiry contracts and redacted timing/categories; separate video/HAR modes are not supplied |
| Accessibility/visual regression/performance budgets | Built in | Pinned axe audits, exact expiring waivers, reviewed environment-specific visual baselines and actual timing budgets; incomplete/unknown checks fail |
| Dataset suites and dependency-aware JUnit execution | Built in | Preflight, selection, independent contexts, explicit setup dependencies, continuation/fail-fast and aggregate verdicts |
| Parallel workers, browser pooling and sharding | Partial | 1..8 isolated suite workers, dependency/resource/identity locks and scoped browser reuse; Java remains sequential; distributed sharding is outside this implementation |
| Cross-browser and native Salesforce Mobile | Partial | Real Chromium/Firefox/WebKit contract checks, separate coverage namespaces, Android/iOS W3C sessions/persistent reviewed fallback/private evidence; actual Mobile/device certification remains target work |
| Apex/trigger/Flow server tests and LWC Jest | External layer | Execute pinned consumer tool commands and securely import distinct JUnit layers; failed/missing/stale/skipped evidence cannot pass |
| Salesforce release/metadata regression certification | Partial | Preview/current matrix provenance, independent combined gates and reviewed schema fingerprints; actual release targets and coverage are supplied by the installation |

## Implemented development tracks

| Priority | Work | Acceptance evidence required before marking it supported |
| --- | --- | --- |
| P0 | Read-only Salesforce API oracle and bounded eventual-result polling | Configurable API version, authorized identity, paginated results, exact IDs/fields, API usage accounting, redaction, wrong-result/timeout/permission fault checks; read retries never repeat mutations |
| P0 | Owned fixture provider, optionally using Composite/sObject Collections | Metadata-aware input validation, supplied values, parent dependencies, per-record results, explicit partial-failure handling and unique per-worker ownership; uncertain commits reconciled before another write |
| P0 | Action/step/report timing and failure classification | Metadata-only durations, per-attempt ownership, persisted totals, environment/action/assertion/evidence categories, assertions proving metrics cannot change the verdict |
| P1 | Controlled browser lifetime and isolated parallel workers | Each worker owns its Playwright thread/runtime; fresh context/state per dataset/persona, bounded resources, org/user/record locks, validated dependency scheduling and cancellation; no shared mutable case data |
| P1 | File and popup/dialog operations | Explicit user-authored step kinds, owned output paths, checksums, bounded sizes, content assertions, masking/redaction, frame/page identity and replay compatibility |
| P1 | Generic Salesforce widget adapters | Synthetic/open/closed-shadow fixtures for lookup results, dependent/multi-select picklists, related lists/virtualized grids, rich text and console workspace tabs; duplicate/stale/permission negatives |
| P1 | Failure-only diagnostic tracing with a private-artifact policy | Capture after trusted auth only, account for DOM/network contents, restricted retention/access, verify attachment ownership; never assume PNG masking sanitizes traces |
| P2 | Accessibility, visual and performance modules | Explicit thresholds/baselines, reproducible viewport/fonts/data, known-defect injection, narrowly scoped waivers and independent fail verdicts |
| P2 | Apex/Jest/API results and Salesforce release matrix | Separate runner versions/credentials, distinct test layers in reports, combined failure gate, preview/production-sandbox target matrix and preserved provenance |
| P2 | Mobile and additional web engines | Capability-specific maps, equivalent coverage/evidence boundaries and representative native/mobile flows; Chromium-only CDP probes cannot be reused blindly |

All ten tracks have checked-in executable contracts and targeted evidence in the [ledger](ROADMAP_IMPLEMENTATION.md). Features remain explicit: bounded suite workers, file roots, private tracing and quality-module invocation. Fresh contexts are retained with browser reuse. Additional engines declare tracked-root coverage; native evidence is confidential rather than browser-masked PNG. The [Playwright Java threading contract](https://playwright.dev/java/docs/multithreading) is enforced. Actual org/release/device certification remains adoption evidence.

## Recommended execution strategy

Run fast LWC/Apex/component and API checks on relevant changes, a small provider-free UI smoke set on deployments, and the broader private regression matrix on scheduled or release runs. This is an engineering recommendation, not a bundled schedule. Keep AI learning/re-learning separate from routine deterministic release gates. Use aggregate dataset suites to amortize Allure generation and CLI work, with a unique data identity for each row and meaningful supplied expected outcomes.

Prefer application conditions over fixed sleeps. UTAM's [explicit-wait guidance](https://developer.salesforce.com/docs/platform/utam/references/utam-grammar/explicit-waits.html) and Playwright's [actionability checks](https://playwright.dev/java/docs/actionability) support condition-driven actions/assertions. Reduce retry policy only after slow-loading target cases and known defects still behave correctly. Page load/network quiet alone does not prove a Flow or server-side transaction completed.

For Salesforce base components, evaluate the [official Salesforce UTAM page objects](https://developer.salesforce.com/docs/platform/utam/guide/test-sf-applications.html), with release compatibility as documented there. They are not a drop-in Playwright Java dependency: the [Java integration guide](https://developer.salesforce.com/docs/platform/utam/guide/java-guide.html) uses WebDriver. A future optional backend can coexist with generic semantic recipes, but this repository has not added that backend or certified those page objects.

For API setup, [sObject Collections](https://developer.salesforce.com/docs/platform/api-rest/guide/resources-composite-sobjects-collections.html) can reduce round trips. The shipped provider deliberately starts with one-shot sequential creates and explicit per-record/uncertain outcomes; a later bulk transport must preserve these contracts. The shipped [SOQL query](https://developer.salesforce.com/docs/platform/api-rest/guide/resources-query.html) oracle follows safe bounded continuations rather than treating one page as complete.

Private [traces](https://playwright.dev/java/docs/api/class-tracing) remain correlated with independent assertion/event verdicts; the tracing API does not record framework assertions itself. The bundled axe module retains the [documented automated accessibility limits](https://playwright.dev/java/docs/accessibility-testing). Owned [downloads](https://playwright.dev/java/docs/downloads) are persisted and verified before their context closes.

## Adoption acceptance checklist

1. Declare the target surfaces, identities, permissions, locales, data fixtures and critical business outcomes; a generic template cannot supply their coverage.
2. Review learned intent/maps/recipes using fixed expected values and representative positive, validation, permission and integration-failure cases.
3. Demonstrate a fresh provider-offline replay, persisted healing followed by strict restart, and rejection of known wrong outcomes.
4. Record first-pass success, intermittent-failure rate, verified defect rejection, AI calls/known usage/estimated cost and per-phase p50/p95 latency. Local five-sample results do not establish production p95.
5. Evaluate custom widgets/frames/Shadow DOM, popup/file requirements and actual session expiry before declaring the deployment supported.
6. Verify complete Allure/raw evidence, failure exit codes and independent CI gates. Separate platform/component/API/mobile coverage from browser E2E evidence.

Current executable evidence and repeatable measurement commands are in [verification](VERIFICATION.md), [efficiency](EFFICIENCY.md), [AI workflow](AI_WORKFLOW.md) and [case workflows](CASE_WORKFLOWS.md).
