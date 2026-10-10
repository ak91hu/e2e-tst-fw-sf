# Framework development guide

[Documentation index](README.md) · [Project README](../README.md) · [Agent instructions](../AGENTS.md)

This guide is for changing the reusable framework. It gives agents and maintainers a bounded path from a task to its contracts, source, regression checks and handoff. Org-specific business cases belong to consuming projects; keep the distributed `sf-e2e-tests` workspace empty.

## Contents

- [First task workflow](#first-task-workflow)
- [Build and runtime prerequisites](#build-and-runtime-prerequisites)
- [Source and test map](#source-and-test-map)
- [Execution and ownership contracts](#execution-and-ownership-contracts)
- [Verification commands](#verification-commands)
- [Change recipes](#change-recipes)
- [Compatibility and dependency changes](#compatibility-and-dependency-changes)
- [Artifacts and path ownership](#artifacts-and-path-ownership)
- [Failure triage](#failure-triage)
- [Documentation maintenance](#documentation-maintenance)
- [Completion and handoff](#completion-and-handoff)

## First task workflow

With only a packaged CLI, start with `java -jar <absolute-cli-path> guide --list` and `guide development`; all listed Markdown topics are embedded at build time and require no config or login. `guide best-practices` gives the pinned [reference audit](REFERENCE_PRACTICES.md). A source change still requires a matching checkout/build and the owning module's regression checks.

1. Inspect the current branch/worktree with `git status --short`. Existing modifications may be work from a previous task; read and preserve them.
2. Restate the requested behavior and identify its owner using the source map below. Read the relevant guide, public contract and existing tests.
3. Follow the actual call path from CLI/JUnit entry to core logic and engine/reporting boundaries. Determine where a failure is classified and where resources/verdicts are finalized.
4. Make the smallest coherent implementation change. Add a synthetic regression for a defect or a new contract when it supplies meaningful behavioral evidence. Use local fixtures rather than a real org or paid provider.
5. Run the focused command and inspect the named suite's result. Extend verification to dependent modules and browser/reporting acceptance as required by the change.
6. Update the owning documentation and configuration/CLI examples. Verify links, examples and whitespace.
7. Hand off the outcome with exact check scope and limitations. A missing browser/runtime is an environment result, not evidence that the implementation passed.

Use `rg --files` to inventory an affected module, `rg -n 'SymbolName' <module>` to find callers and `rg -n '^## ' docs/<guide>.md` to inspect a guide before reading it in full. Do not rely on previously built classes or an old shaded jar after changing sources.

## Build and runtime prerequisites

The checked CI build uses Temurin JDK 27, Maven Wrapper 3.10.0 and Node.js 26.10.0 / npm 12.2.0. Java compilation targets release 21. Exact pins and upgrade notes belong to [DEPENDENCIES.md](DEPENDENCIES.md), the root/module POMs, `.mvn/wrapper/maven-wrapper.properties` and `sf-e2e-reporting/allure-engine/package-lock.json`.

Configure a JDK on `JAVA_HOME`/`PATH` and check `java -version`, `node --version` and `mvnw.cmd --version` before attributing a build failure to code. An installed Java 17 cannot compile this project. `.tools/` JDK folders are optional local tooling, not repository requirements.

The reporting module runs `node sf-e2e-reporting/allure-engine/bundle.mjs` during Maven `generate-resources`. First use can install locked npm dependencies; subsequent builds reuse the hash-validated bundle. Report tests and rendering also need Node. The generated archive/resources under `target/` are not sources to edit.

Framework checks need no org/model credentials. Core-focused checks need no browser installation. Full verification requires Chromium, Firefox and WebKit from the project's Playwright version; install them after building the CLI. Linux also needs the browser system libraries. Consumer execution requires its own target configuration/session; it is separate from framework verification.

## Source and test map

Production classes below are relative to each module's `src/main/java/hu/ak91/sf/e2e/` package. Regression classes live under that module's `src/test/java`. Use the actual source path and symbol rather than guessing a new architecture.

| Area / module | Production entry points | Primary regression checks | Owning guide |
| --- | --- | --- | --- |
| Core configuration, values, state | `core/FrameworkConfig`, `Deadline`, `SecretRegistry`, `BrowserState`, `Json`, `UiVerification` | `CoreContractsTest`, `BrowserStateTest`, `JsonCompatibilityTest`, `QualityAssertionsTest` | [Implementation](IMPLEMENTATION.md) |
| Locators and page APIs / core | `core/locator/LocatorStore`, `LocatorEntry`, `HealingLocators`; `core/page/PageObjects`, `PageObject` | `HealingLocatorsTest`, `LocatorSnapshotTest`, `PageObjectContractsTest` | [Page Object Model](PAGE_OBJECT_MODEL.md) |
| Recipes, readiness, selection / core | `core/scenario/CaseSpec`, `CaseRecipe`, `CaseRunner`, `UiReadiness`, `CaseNames`, `CaseSuite`, `DatasetScheduler`, `ChangeImpact` | `CaseRecipeTest`, `UiReadinessTest`, `RoadmapContractsTest`, `ChangeImpactTest` | [Case workflows](CASE_WORKFLOWS.md), [Advanced capabilities](ADVANCED_CAPABILITIES.md) |
| Browser engine / engine-playwright | `engine/PlaywrightEngine`; resource `src/main/resources/hu/ak91/sf/e2e/engine/semantic.js` | `EngineHarnessIT`, `GeneralUiHarnessIT`, `HealingHarnessIT`, `QualityHarnessIT`, `RoadmapHarnessIT`, `ReliabilityHarnessIT` | [Implementation](IMPLEMENTATION.md), [Reliability review](RELIABILITY_REVIEW.md) |
| AI learning / agent | `agent/UiMapper`, `CasePlanner`, `CaseLearner`, `CaseTargetReuse`, `CaseTargetIndex`, `UiExplorer`, `AiWorkflowClient`, `JsonModelClient`, `ModelClient`; `replay/CacheStore` | `UiMapperTest`, `CasePlannerTest`, `CaseLearnerTest`, `CaseTargetReuseTest`, `AiWorkflowClientTest`, `AiRequestEfficiencyTest`, `ModelProtocolTest`, `AgentReplayTest` and `*HarnessIT` | [AI workflow](AI_WORKFLOW.md) |
| Authentication / salesforce | `salesforce/auth/JwtAuthenticator`; `core/BrowserState` defines external browser-state handling | `AuthenticationJournalTest`, `auth/FrontdoorSecretsTest`, core `BrowserStateTest` | [Implementation](IMPLEMENTATION.md) |
| REST and owned data / salesforce | `salesforce/api/SalesforceApi`, `OwnedFixtures`; `salesforce/fixture/RecordRegistry`, `OwnedRecord`, `FixtureDataFactory` | `RestContractsTest`, `AuthenticationJournalTest` | [Advanced capabilities](ADVANCED_CAPABILITIES.md) |
| Evidence and Allure / reporting | `reporting/EvidenceService`, `ReportIntegrityVerifier`, `ReportDirectories`, `WorkflowReportExporter`, `TestDesignReport`, `SuiteReportExporter`, `AllureReports`, `AllureResults` | `ReportIntegrityTest`, `ReportDirectoriesTest`, `AllureReportingTest`, `TestDesignReportTest`, `AiCostReportingTest` | [Allure reporting](ALLURE_REPORTING.md) |
| Trends and external layers / reporting | `reporting/TrendReports`, `ExternalLayers`; core `scenario/ChangeImpact` | `TrendReportsTest`, `ExternalLayersTest`, core `ChangeImpactTest` | [Efficiency workflows](EFFICIENCY_WORKFLOWS.md) |
| Native driver / mobile | `mobile/MobileSession` | `MobileContractsTest` using local W3C-shaped services | [Advanced capabilities](ADVANCED_CAPABILITIES.md) |
| JUnit lifecycle / junit | `junit/SelectionPlanner`, `DependencyMethodOrderer`, `RunCoordinator`, `RunSession`, `SfE2eExtension`, `SfTestContext`, `SfCase` | `SelectionPlannerTest`, `ExecutionLifecycleTest`, `LifecycleDependencyTest`, `BrowserQualityLifecycleTest` | [Test structure review](TEST_STRUCTURE_REVIEW.md) |
| CLI / cli | `cli/SfE2eCli`, `CaseCommands`, `SuiteCommands`, `ImpactCommands`, `ComponentCommands`, `ReleaseCommands`, `TrendCommands` | `SfE2eCliTest`, `CaseCommandsTest`, `SuiteCommandsTest`, `ImpactCommandsTest`, `ComponentCommandsTest`, `ReleaseCommandsTest` | [README command reference](../README.md#command-reference) |
| Autonomous integration / core, agent, cli, engine | `core/scenario/ExplorationCharter`, `SuiteSelection`; `core/SessionVault`; `agent/CaseExplorer`; `cli/ExplorationCommands`, `McpCommands`, `SessionCommands`, `VerificationSkillCommand`; engine secret fill/export | `ExplorationCharterTest`, `SuiteSelectionTest`, `SessionVaultTest`, `CaseExplorerTest`, `McpCommandsTest`, `SuiteCommandsTest`, `SessionSecurityHarnessIT`, `CaseWorkflowHarnessIT` | [Autonomous workflows](AUTONOMOUS_WORKFLOWS.md#implementation-and-regression-map) |
| Node tools / scripts | `lwc-runner.mjs`, `jest-reporter.cjs`, `collect-changes.mjs`, `model-output-limit.mjs`, `run-with-env.mjs` | `component-adapter.test.mjs`, `collect-changes.test.mjs`, `model-output-limit.test.mjs` | [Efficiency workflows](EFFICIENCY_WORKFLOWS.md), [AI workflow](AI_WORKFLOW.md) |

`sf-e2e-tests` is an empty integration workspace, not the home of framework regressions. `sf-e2e-cli` packages dependencies into `target/sf-e2e.jar`; the jar's existence alone does not show that checks ran.

## Execution and ownership contracts

### Learning and replay

CLI learning/discovery/mapping uses a bounded model pipeline to identify observed controls or plan supplied intent. Independently validate references, scope, completeness, identity and current state before publishing locators/recipes. `CaseRunner` executes saved steps through session page objects and verifies caller-supplied expected results without constructing a provider. Page wrappers are reusable; observation nodes are not.

Learning a supplied business case executes its actions. Mapping observes controls; discovery follows eligible visible navigation; replay independently establishes the current outcome. These operations have distinct side effects and coverage claims. See [CASE_WORKFLOWS.md](CASE_WORKFLOWS.md).

### Actions, threads and deadlines

`UiEngine.perform` may report `NODE_STALE` only before dispatch. All failures after an attempted interaction need a different classification. Read-only polling/assertions may repeat; a potentially committed mutation may not. Retain exact expected values and report uncertain outcomes.

Playwright runtimes, contexts and page APIs use their owner thread. Dataset workers may reuse a browser process but create fresh contexts for each row. Java consumer dependency execution uses one coordinated owner thread. Close partial contexts after failed initialization and preserve idempotent cleanup. Cap explicit UI operation timeouts by caller and attempt budgets; deadlines are not a watchdog for arbitrary user Java or unbounded browser evaluation.

### Final verdict and evidence

JUnit user teardown runs while its case context is open. Finalization incorporates teardown, later JUnit callbacks and class/container failures before releasing dependencies. Every selected dataset retains its outcome, including failed and skipped rows. A passed-looking UI action is insufficient when quality gates, evidence, journals or aggregate integrity fail.

Do not weaken masking, identity confidence, complete-observation requirements, private evidence ownership, atomic publication or repository checksums to make a check pass. Synthetic fixtures exercise these framework contracts; project-specific expected business outcomes remain caller inputs.

## Verification commands

Commands below run from the repository root in PowerShell. Use `./mvnw` in place of `.\mvnw.cmd` on Linux/macOS. Keep property arguments quoted in PowerShell. `-am` builds upstream reactor modules, preventing stale installed snapshot dependencies from standing in for the current source.

### Fast unit and component checks

Run the affected module and upstream dependencies, for example:

```powershell
.\mvnw.cmd -B -ntp -pl sf-e2e-core -am test
.\mvnw.cmd -B -ntp -pl sf-e2e-junit -am test
```

The second command also builds reporting and therefore needs Node. Maven `test` runs Surefire unit/component checks, including synthetic framework lifecycle fixtures; it does not run the Failsafe browser harnesses.

Select an actual core regression when narrowing a defect investigation:

```powershell
.\mvnw.cmd -B -ntp -pl sf-e2e-core -am test "-Dtest=UiReadinessTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

The no-specified-tests option permits upstream modules without that class. Confirm `UiReadinessTest` appears with its expected executions in the output. Do not treat a misspelled selector and an empty result as acceptance.

### Focused real-browser harness

With the browser binaries already installed:

```powershell
.\mvnw.cmd -B -ntp -pl sf-e2e-engine-playwright -am verify -Pchecks "-Dit.test=ReliabilityHarnessIT" "-Dfailsafe.failIfNoSpecifiedTests=false"
```

Surefire checks still run in the selected reactor; Failsafe selects the named browser harness. The engine, agent and CLI POMs bind `*HarnessIT` under Maven profile `checks`. New real-browser checks in those modules must follow that naming/binding contract or explicitly update the POM and its verification scope. Failsafe results are finalized in `verify`.

### First build and full acceptance

```powershell
.\mvnw.cmd -B -ntp package -DskipTests
java -cp sf-e2e-cli/target/sf-e2e.jar com.microsoft.playwright.CLI install chromium firefox webkit
.\mvnw.cmd -B -ntp verify -Pchecks
node --test scripts/model-output-limit.test.mjs scripts/component-adapter.test.mjs scripts/collect-changes.test.mjs
node scripts/check-project-language.mjs
git diff --check
```

On Linux use `install --with-deps chromium firefox webkit` for required system libraries. The initial skipped-test build supplies the CLI for browser installation; the later `verify` establishes acceptance. Maven profile `-Pchecks` selects build-time harness bindings. Runtime YAML/CLI `profile: checks` or `--profile checks` is a separate configuration concept and must not be used for live consumer execution.

Inspect `target/surefire-reports` and `target/failsafe-reports` in the affected modules, the reactor summary and the selected suites. Record failures, errors and skips as well as the total. Current inventory and prior receipts belong to [VERIFICATION.md](VERIFICATION.md); calculate a new inventory from the run rather than copying an earlier count.

### Credential-free CLI smoke and documentation changes

After packaging current sources:

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar --help
java -jar sf-e2e-cli/target/sf-e2e.jar doctor --profile checks
java -jar sf-e2e-cli/target/sf-e2e.jar list --json
```

The empty template lists `[]`. Explicit `checks` doctor validates local runtime/configuration without logging into an org. `run` without supplied classes returns `NO_TESTS` before authentication. None of these smoke commands establishes target application coverage.

For Markdown-only changes, run `node scripts/check-project-language.mjs` and `git diff --check`. The validator checks English vocabulary, local paths, heading anchors and closed code fences; it does not compile Java examples, parse every JSON/YAML snippet or prove CLI semantics. For changed executable examples, compile/parse them or check the corresponding current CLI help and production record/schema as appropriate.

## Change recipes

### Add a recipe action or assertion

Trace `CaseSpec` allowed kinds/validation, `CaseRecipe` parameter/preflight/checksum rules, `CaseLearner` target/value planning and `CaseRunner` dispatch/assertions. For an action, inspect `UiEngine` and `PlaywrightEngine.perform`; for a read-only assertion, preserve its bounded polling and failure contract. Native capability support is a separate decision, not automatic browser parity.

Add synthetic core validation/negative checks and a real-browser `*HarnessIT` for DOM or asynchronous behavior. Verify saved replay without an available provider and rejection of a wrong expected result where relevant. Update [CASE_WORKFLOWS.md](CASE_WORKFLOWS.md), the relevant [advanced section](ADVANCED_CAPABILITIES.md) and CLI help/examples.

### Change observation, locator identity or healing

Read `semantic.js`, `Observation`, `SemanticTarget`, `LocatorEntry`, `LocatorStore` and `HealingLocators`, then locate mapping freshness checks in `UiMapper`/`CaseLearner`. Establish whether the change affects persisted identity, namespace or engine capability. Test duplicates, frames/scopes, stale observations, incomplete coverage, wrong route/identity, read-only repairs and a fresh restart after persisted repair as applicable.

Preserve byte-level corruption detection; same size or restored mtime cannot authorize snapshot reuse. Parameters for a specific record remain in memory and cannot heal to another record. Update [IMPLEMENTATION.md](IMPLEMENTATION.md), [PAGE_OBJECT_MODEL.md](PAGE_OBJECT_MODEL.md) and compatibility notes when identity semantics change.

### Change JUnit lifecycle or dataset scheduling

Trace selection and dependency closure through `SelectionPlanner`, class ordering through `DependencyMethodOrderer`, execution through `RunCoordinator`/`RunSession`, and context finalization through `SfE2eExtension`/`SfTestContext`. For datasets, inspect `CaseSuite`, `DatasetScheduler`, `ExecutionLocks` and `SuiteCommands` independently.

Use synthetic context factories and local fixtures. Check user/class teardown failure, disabled/aborted cases, dependency blocking, class fixture reuse, late callbacks and exactly one final result. Scheduling changes need exclusion/rendezvous/dependency/cancellation evidence while preserving fresh contexts. Update [TEST_STRUCTURE_REVIEW.md](TEST_STRUCTURE_REVIEW.md) for current lifecycle contracts and [ADVANCED_CAPABILITIES.md](ADVANCED_CAPABILITIES.md) for worker/resource behavior.

### Change AI requests or cost optimizations

Trace `ModelClient`, `JsonModelClient`, `AiWorkflowClient`, `UiMapper` and `CaseLearner`. Bound each physical attempt, serialized input and output allowance; keep missing usage unknown. Test invalid output, stale/invented references, exhausted budgets and transport failures with mock responses/local HTTP. Demonstrate independent unchanged outcome assertions and provider-free replay.

Distinguish wire bytes, output caps, actual token usage, synthetic estimated cost and paid-provider billing. Update [AI_WORKFLOW.md](AI_WORKFLOW.md); add reproducible performance evidence to [EFFICIENCY.md](EFFICIENCY.md) only when measured.

### Change reports, quality or external runners

Trace evidence creation, result state, exporters, integrity verification and renderer publication separately. Test malformed/foreign evidence, inconsistent verdicts, missing PNGs, failed rendering, preserved raw results and affected aggregate failure. Native PNGs and raw trace/download/runner output remain private by their contract.

For Node component tools, inspect `scripts/lwc-runner.mjs`, `jest-reporter.cjs`, CLI packaging resources and `ExternalLayers`; preserve runtime/dependency pins and exact selected test coverage. Run the Node checks and the affected Java reporting/CLI checks. Update [ALLURE_REPORTING.md](ALLURE_REPORTING.md), [EFFICIENCY_WORKFLOWS.md](EFFICIENCY_WORKFLOWS.md) or [ADVANCED_CAPABILITIES.md](ADVANCED_CAPABILITIES.md) according to ownership.

## Compatibility and dependency changes

Versions live in root dependency/plugin management, module-specific plugins, Maven Wrapper properties, the Allure npm package/lockfile and CI pins. Search all references before editing a version, including engine capability strings, badges, copied CLI tooling resources and third-party notices. A root version change alone may not update generated/browser/runtime artifacts.

Persisted recipe timestamps, optional fields, map ordering and checksums are checked by `JsonCompatibilityTest`; preserve these during serialization changes. Locator capability changes require deliberate remapping, not silently accepting old identity semantics. JUnit upgrades need lifecycle/finalization coverage, and Allure upgrades need real generation/rebuild/integrity checks. The exact migration history is in [DEPENDENCIES.md](DEPENDENCIES.md).

For an upgrade, verify official release information, update the dated dependency snapshot, run the relevant migration checks and full acceptance, rebuild the shaded CLI, and validate Java 21 runtime compatibility for changed runtime paths. Do not label an old snapshot as the newest available upstream version.

## Artifacts and path ownership

| Location | Ownership and handling |
| --- | --- |
| `sf-e2e-*/src/`, POMs, `scripts/`, tracked example/config files | Distributable framework sources and generic examples |
| `sf-e2e-*/target/` | Generated classes, resources, jars and Maven check reports; rebuilt from source |
| `.sf-e2e/locators`, `.sf-e2e/cases`, cache directories | Target/persona/capability-specific runtime state; ignored by default |
| `.sf-e2e-data/` | Permanent attempt/owned-record journals, including uncertain outcomes |
| `report/<UTC timestamp>/` | Owned run evidence, results and Allure publication outside Maven clean |
| `.tools/`, `.reference/` | Ignored optional local tools and historical/private receipts; not assumed available |
| `*.local.yaml`, `*.local.json`, `*.local.txt`, `.env`, keys/session files | Consuming-project private inputs; no distribution dependency |

Configured artifact roots resolve relative to the selected YAML file; explicit CLI file arguments resolve from the current working directory; suite recipe/parameter references resolve from the suite manifest. Inspect those bases before diagnosing missing files or changing output locations. Avoid copying a recipe without its matching namespace/capability-specific map.

## Failure triage

| Symptom | First inspection | Valid conclusion / next step |
| --- | --- | --- |
| Compile fails before checks | Java version, `JAVA_HOME`, module POM and wrapper output | Fix the actual environment/API mismatch; no test verdict exists yet |
| Browser launch fails | Selected browser, installed Playwright binaries, Linux system libraries and `ENVIRONMENT_UNAVAILABLE` | Install the required matching runtime, then rerun the affected harness |
| Reporting build/render fails | Node version/PATH, bundle execution and retained `allure-generation.log` | Preserve raw results; inspect the failed build/render boundary |
| Green targeted run but missing expected suite | Selector, module, Maven phase/profile and report output | Correct selection; zero checks cannot prove the requested behavior |
| Locator drift/ambiguity | Fresh complete observation, scope/frame, stable anchors, route and repository/capability | Preserve explicit rejection until identity is independently established |
| Action may have committed | Dispatch boundary, journal, independent current outcome and failure code | Retain uncertainty; do not rerun a mutation merely to obtain green output |
| Framework says passed but JUnit/aggregate failed | Teardown/container callbacks, finalization order, exporter and integrity verification | Reconcile the final verdict and dependent release |
| Historical evidence path is absent | Whether it is ignored local evidence versus tracked source | Reproduce with current documented commands; do not require a private receipt |

Synthetic negative checks intentionally produce failed/blocked report artifacts and nonzero subprocess results. Assess their enclosing assertion and final Maven/Node result; a line saying a deliberately faulty dataset failed is not by itself a framework-test failure.

## Documentation maintenance

Keep these responsibilities distinct:

| Artifact | Update when |
| --- | --- |
| [AGENTS.md](../AGENTS.md) | Repository-wide maintainer invariants or agent workflow change |
| [README.md](../README.md) | User entry points, setup, command examples or public behavior change |
| [Documentation index](README.md) | A guide is added/renamed or task reading paths change |
| Owning feature guide | Inputs, outputs, validation, failure behavior or compatibility change |
| [REQUIREMENTS.md](REQUIREMENTS.md) / [implementation ledger](ROADMAP_IMPLEMENTATION.md) | Capability-to-source/test traceability changes |
| [VERIFICATION.md](VERIFICATION.md) | New executed acceptance evidence is recorded; retain previous evidence as dated history |
| [DEPENDENCIES.md](DEPENDENCIES.md) / [THIRD_PARTY.md](THIRD_PARTY.md) | Tool pins, migration requirements or bundled software/notices change |

Use English, one descriptive H1, clear H2/H3 sections and task-oriented tables for mappings. Longer guides should have a local contents list. Add index/project/agent links so an agent can enter through any feature guide. State prerequisites, exact commands, expected outputs, failure semantics and artifact ownership where applicable.

Keep examples generic and separate caller values from independent expected outcomes. Link to the owning contract rather than duplicating large policy tables. Use current source paths and verify local links/anchors. Do not overwrite a dated benchmark or append an unsupported passing count. The language/link script includes root agent guidance, documentation and module READMEs; its passing result still needs a semantic review of changed examples.

## Completion and handoff

A framework change is ready when the requested behavior is implemented, the relevant negative and positive contracts are verified, the matching public documentation is current, and the diff preserves org-independence and compatibility requirements. State which commands actually ran and which source scope they cover.

For an agent handoff, include the objective, changed files, implementation decisions, successful/failed checks, artifacts needed to reproduce results and unresolved items. Preserve active work and user constraints across context resets. Separate framework acceptance from consumer application coverage, local fixtures from live targets, and recorded historical receipts from current execution evidence.
