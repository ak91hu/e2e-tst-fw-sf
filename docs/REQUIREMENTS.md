# Requirement and best-practice audit

[Documentation index](README.md) · [Project README](../README.md) · [Agent instructions](../AGENTS.md) · [Development guide](DEVELOPMENT.md)

The deliverable is a reusable Java Salesforce UI framework. Consumer business tests and deployment-specific maps remain outside the template. Internal verification harnesses validate framework behavior without shipping customer workflows.

## Contents

- [Requested capabilities](#requested-capabilities)
- [Practices adapted from the supplied documentation](#practices-adapted-from-the-supplied-documentation)
- [Safety of repairs](#safety-of-repairs)

## Requested capabilities

The [pinned TesterArmy reference audit](REFERENCE_PRACTICES.md) supplements this capability ledger with 28 applicable practices, their concrete source/checks, corrected faults and intentional product differences. `GuideCommandTest` verifies that every whitelisted public guide is bundled and readable without config/credentials; agent/cache/wait and secret/pixel regressions enforce the adopted execution and security boundaries. The audit does not claim TypeScript API, hosted service, MCP or autonomous mutating exploration parity.

| Requirement | Implementation | Verification |
| --- | --- | --- |
| Empty framework, no bundled business cases | `sf-e2e-tests/src/test/java` contains only `.gitkeep`; classes are explicitly selected by the consumer; mapping/inspection need no case file | `SelectionPlannerTest`, `SfE2eCliTest`, CLI `list --json`, packaged-class inspection |
| AI discovers the app UI | `UiMapper` maps explicit scopes; `UiExplorer` autonomously selects eligible visible navigation routes within hard limits | `UiMapperTest`, `ModelProtocolTest`, `MappingLifecycleHarnessIT`, `CaseWorkflowHarnessIT` |
| Cost-efficient AI operation without weakening quality | Default `AiWorkflowClient`: shared budgets, compact complete discovery, adaptive output caps, bounded target batches with fresh rematching/invalidation, unchanged-scope reuse, bounded repair/escalation and Allure byte/token/cost metrics | `AiWorkflowClientTest`, `AiRequestEfficiencyTest`, `BatchLearningHarnessIT`, `UiMapperTest`, `ModelProtocolTest`, `AiCostReportingTest`; [policy and evaluation guide](AI_WORKFLOW.md) |
| Learn a supplied test case and replay without AI | `learn-case` accepts JSON or text, resolves each target, verifies explicit outcomes and saves a checksummed recipe; text literals are validated against supplied intent and saved as private runtime parameters | `CaseLearnerTest`, `CaseRecipeTest`, `CaseCommandsTest`, cross-page Chromium learn/save/provider-offline replay/heal/restart |
| Build and store reusable locators | Semantic role/name/label/container plus stable fingerprints and alternatives; namespaced atomic JSON repository with checksums, revisions, locking and remap archives | `UiMapperTest`, `HealingLocatorsTest` |
| Default Page Object Model | `PageObjects`/`PageObject` under learning/replay, route-context model groups, session/persona/worker wrapper reuse and `SfTestContext.page(id)`; fresh resolution and case-owned assertions | `PageObjectContractsTest`, `ExecutionLifecycleTest`, cross-page provider-offline replay/heal/restart |
| TST_ names for generated cases | `CaseNames` normalizes structured/text/discovery IDs and CLI recipe filenames before model/authentication dispatch; existing IDs/checksums remain compatible | Naming boundaries, output collision protection and cross-page `TST_` identity checks |
| Fast checked locator reads | Immutable validated snapshot per store; bounded current-byte hashing on every read, full validation on content change, detection independent of size/mtime | `LocatorSnapshotTest`, repeated cold/warm store benchmark |
| Delayed/frame-owned custom picklist selection | Bounded condition wait after one opening click; observed frame and ARIA-controlled/owned container; exact visible name and duplicate rejection | `EngineHarnessIT`, `GeneralUiHarnessIT` |
| Org-independent framework | Configured origins, application routes, auth endpoints, identity aliases, language/locale/timezone and optional custom-object journals; no built-in customer/object workflows | `CoreContractsTest`, `BrowserStateTest`, `GeneralUiHarnessIT`, `AuthenticationJournalTest` |
| Automatic self-healing with retries | Fresh bounded primary retries, unique alternatives, fingerprint ranking, independent confidence/margin checks, safe promotion/history and persistent read-write updates | `HealingLocatorsTest`, `HealingHarnessIT`, `MappingLifecycleHarnessIT` |
| Future cases run without AI | Deterministic profile and `HealingLocators` do not construct a model; optional fallback is disabled; expected outcomes stay explicit | Full mapping lifecycle: provider stopped after mapping, fresh contexts execute, repair, persist and restart successfully |
| Automatic Allure reports and default screenshots | Bundled Allure 3.20.1 renders single-file HTML after JUnit execution and learn/discover/replay; masked PNGs attach to completed steps and failures; rebuild/verify supports both report formats | `AllureReportingTest`, `ReportIntegrityTest`, live zero-model replay and browser inspection of generated HTML |
| Searchable timestamped reports | Every reporting workflow reserves `report/<precise UTC timestamp>` atomically; exact timestamp collisions get a unique suffix; historical evidence is preserved | `ReportDirectoriesTest`, coordinated JUnit lifecycle checks and live timestamped replay |
| Quality-focused defect detection | Browser/console/5xx/transport gates, bounded diagnostics, exact expiring waivers, drift limits and stronger read-only state/count/absence oracles | Browser/Chromium/JUnit fault-injection checks and empty/missing verification rejection |
| Dataset-driven regression without AI | Private suite manifest, complete preflight, filtered rows, fresh browser per dataset, continue/fail-fast/cancellation, verified aggregate Allure | `SuiteCommandsTest`, real recipe learn/provider-free replay harness and live suite validation |
| Detailed English engineer guidance | Expanded README with authoring/risk/data/oracle/triage/CI examples, primary-source audit and repeatable English/link checks | `check-project-language.mjs`, manual text/API review and verified commands |
| English framework and detailed README with badges | CLI messages, public documentation, examples and configuration comments use English; README covers setup, architecture, mapping, persistence, modes, CI and troubleshooting | Documentation/source inspection; badges match root POM and Maven Wrapper versions |

Consumer lifecycle correctness is covered by `ExecutionLifecycleTest`, `SelectionPlannerTest` and `LifecycleDependencyTest`: setup dependencies, class-scoped fixtures, teardown/late callback verdicts, disabled cases, no-model dependency closure and rejection of direct launches outside the coordinator. See the [resolved structure review](TEST_STRUCTURE_REVIEW.md).

"Org-independent" describes configuration and reusable execution contracts. It does not imply that all editions, authentication policies, installed packages and inaccessible UI implementations have been live-tested. The consumer supplies an authorized session and maps the actual app's controls. Autonomous discovery covers eligible visible navigation links within configured limits; supplied case intent covers hidden/form workflows. See [case workflows](CASE_WORKFLOWS.md).

The [implementation ledger](ROADMAP_IMPLEMENTATION.md) records all ten subsequent tracks: REST/fixtures, timing, scheduling, file/page/widget operations, private tracing, quality modules, release layers and additional engines/native sessions. [Advanced capabilities](ADVANCED_CAPABILITIES.md) defines usage and tested boundaries; [the review](SALESFORCE_CAPABILITY_REVIEW.md) separates implementation from private org/device certification and further bulk/event/custom-widget integrations.

## Practices adapted from the supplied documentation

These are Java/Salesforce implementations of applicable design practices, rather than an API clone or a claim to implement every feature of the reference product.

| Practice and source | Framework implementation | Evidence |
| --- | --- | --- |
| Deterministic reuse, semantic identity and explicit ambiguity handling: [cache](https://e2e.tester.army/docs/cache) | Role/name/label/scope and stable fingerprints; no transient DOM-ID/position guessing; exact matches and repairs retain frame identity | Core and Chromium healing harnesses |
| Context-aware reuse: [cache](https://e2e.tester.army/docs/cache) | Per project/org/identity/UI namespaces, engine-version gate, normalized record routes, meaningful query digest | Core contracts and wrong-context/query checks |
| Verified action replay: [cache](https://e2e.tester.army/docs/cache) | Optional agent action recordings require independent subsequent outcome proof; locator resolution and business assertions stay separate | `AgentReplayTest`; full lifecycle checks an actual UI action outcome |
| Controlled storage modes: [cache](https://e2e.tester.army/docs/cache) | Local read-write, CI read-only and explicit strict mode; read-only repairs stay in memory | `HealingHarnessIT`, integrity/concurrency checks |
| Treat app content as untrusted evidence: [security](https://e2e.tester.army/docs/security) | Read-only mapping, closed output schema, current node references, no model-generated selector/code evaluation and fresh validation before writes | Invalid/invented/omitted/stale mapping checks |
| Bound model work and protect credentials: [security](https://e2e.tester.army/docs/security), [exploration](https://e2e.tester.army/docs/explore) | External credential references, call/token/time limits, value-free mapping projection, redacted reports and masked/withheld screenshots | Model protocol, mapper, authentication and evidence checks |
| Investigate without writing a business test: [exploration](https://e2e.tester.army/docs/explore) | `inspect-ui`, `map-ui` and `verify-ui` operate on an explicit route/plan and emit evidence | CLI readiness checks and live validation reports |
| Repeatable builds, preserved failure evidence and external CI secrets: [CI](https://e2e.tester.army/docs/ci) | JDK 27 build / Java 21 bytecode / Maven Wrapper / pinned dependencies and action SHAs, Windows/Linux checks, artifact upload, manual deterministic org execution and org concurrency lock | `.github/workflows`, complete Maven verification and report-integrity checks |

## Safety of repairs

Retrying locator discovery is separate from retrying a business mutation. Only a pre-dispatch stale-node rejection permits action resolution to repeat; an uncertain dispatched action is not replayed. Record-link parameters remain in memory and cannot heal to another record. Healing changes the control description, never the expected result. Missing identity evidence, duplicate targets, wrong routes/frames and incomplete observations fail with explicit codes.

See [verification](VERIFICATION.md) for the checked suites and live evidence, and [README](../README.md) for runnable commands and configuration.
