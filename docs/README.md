# Documentation index

[Project README](../README.md) · [Agent instructions](../AGENTS.md) · [Development guide](DEVELOPMENT.md)

Use this index to read the smallest set of documents needed for a task. The project README explains product usage; the development guide explains how to change the framework. All framework-maintenance work preserves an empty consumer test module and org-independent behavior.

## Reading paths

| Task | Read in order | Result |
| --- | --- | --- |
| First maintenance task | [Agent instructions](../AGENTS.md), [Development guide](DEVELOPMENT.md), [Implementation](IMPLEMENTATION.md) | Scope, invariants, source ownership and checks |
| Find a CLI workflow | [README workflow selector](../README.md#choose-a-workflow), [Case workflows](CASE_WORKFLOWS.md) | Correct command and expected artifacts |
| Change controls, healing or page APIs | [Page Object Model](PAGE_OBJECT_MODEL.md), [Implementation](IMPLEMENTATION.md), [Reliability review](RELIABILITY_REVIEW.md) | Fresh-state, identity, retry and cleanup contracts |
| Change AI learning or transport | [AI workflow](AI_WORKFLOW.md), [Case workflows](CASE_WORKFLOWS.md), [Development guide](DEVELOPMENT.md#change-recipes) | Request/budget boundaries and provider-free replay checks |
| Change JUnit lifecycle or scheduling | [Test structure review](TEST_STRUCTURE_REVIEW.md), [Advanced capabilities](ADVANCED_CAPABILITIES.md), [Development guide](DEVELOPMENT.md#change-recipes) | Teardown, dependencies, owner threads and resource locks |
| Change evidence or reports | [Allure reporting](ALLURE_REPORTING.md), [Implementation](IMPLEMENTATION.md), [Development guide](DEVELOPMENT.md#change-recipes) | Ownership, masking, final verdict and publication integrity |
| Change REST, fixtures, widgets or native adapters | [Advanced capabilities](ADVANCED_CAPABILITIES.md), [Implementation ledger](ROADMAP_IMPLEMENTATION.md) | Supported inputs, failure behavior and adoption boundaries |
| Change impact selection, trends or component dispatch | [Efficiency workflows](EFFICIENCY_WORKFLOWS.md), [Development guide](DEVELOPMENT.md#source-and-test-map) | Reviewed selection and independent layer verdicts |
| Upgrade dependencies | [Dependency snapshot](DEPENDENCIES.md), [Development guide](DEVELOPMENT.md#compatibility-and-dependency-changes), [Verification](VERIFICATION.md) | Pin locations, migration risks and acceptance evidence |
| Investigate performance | [Efficiency assessment](EFFICIENCY.md), [AI workflow](AI_WORKFLOW.md), [Reliability review](RELIABILITY_REVIEW.md) | Reproducible measurements and the limits of their claims |
| Evaluate an existing verification claim | [Verification](VERIFICATION.md), the referenced dated review, the relevant source/test | Actual scope, provenance and reproducibility |

## Current contracts and procedures

These documents describe implementation or operation. Consult current source if a described behavior and the worktree disagree.

| Document | Owns |
| --- | --- |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Maintainer workflow, source/test map, focused commands, change recipes and documentation maintenance |
| [IMPLEMENTATION.md](IMPLEMENTATION.md) | Authentication, mapping/storage, deterministic execution, healing and evidence contracts |
| [PAGE_OBJECT_MODEL.md](PAGE_OBJECT_MODEL.md) | Default page APIs, session reuse, composition, naming and historical recipe compatibility |
| [CASE_WORKFLOWS.md](CASE_WORKFLOWS.md) | Structured/text learning, discovery, replay, parameters and dataset suites |
| [AI_WORKFLOW.md](AI_WORKFLOW.md) | Shared budgets, wire/input/output limits, reuse, batching, escalation and accounting |
| [ALLURE_REPORTING.md](ALLURE_REPORTING.md) | Evidence ownership, screenshots, artifacts, renderer, rebuild and verification |
| [ADVANCED_CAPABILITIES.md](ADVANCED_CAPABILITIES.md) | REST/fixtures, scheduling, files/pages/widgets, quality, external layers and native sessions |
| [EFFICIENCY_WORKFLOWS.md](EFFICIENCY_WORKFLOWS.md) | Change-impact selection, context-separated trends and pinned LWC/Jest dispatch |
| [REQUIREMENTS.md](REQUIREMENTS.md) | Capability-to-implementation-to-regression traceability |
| [DEPENDENCIES.md](DEPENDENCIES.md) | Dated checked stack, version ownership and migration details |
| [THIRD_PARTY.md](THIRD_PARTY.md) | Bundled software and license/notice obligations |
| [Consumer workspace README](../sf-e2e-tests/README.md) | Empty-distribution policy and integration contract for consuming projects |

## Reviews, measurements and evidence

These documents contain dated findings or receipts. A recorded passing inventory is not a substitute for checking a later worktree. Ignored local evidence paths may be absent in another checkout.

| Document | Use it for |
| --- | --- |
| [VERIFICATION.md](VERIFICATION.md) | Latest recorded full check inventory, commands, runtime compatibility and historical validation boundaries |
| [RELIABILITY_REVIEW.md](RELIABILITY_REVIEW.md) | Readiness state, asynchronous selection, attempt budgets and failed/repeated runtime cleanup |
| [TEST_STRUCTURE_REVIEW.md](TEST_STRUCTURE_REVIEW.md) | JUnit lifecycle findings, corrections and supported execution ordering |
| [QUALITY_RESEARCH.md](QUALITY_RESEARCH.md) | Primary sources and adopted defect-detection practices |
| [EFFICIENCY.md](EFFICIENCY.md) | Benchmark methodology, measurements, costs and remaining performance priorities |
| [SALESFORCE_CAPABILITY_REVIEW.md](SALESFORCE_CAPABILITY_REVIEW.md) | Capability assessment, acceptance priorities and implementation/adoption boundaries |
| [ROADMAP_IMPLEMENTATION.md](ROADMAP_IMPLEMENTATION.md) | Implemented development tracks and their acceptance evidence |

## Source of truth

| Question | Authoritative location |
| --- | --- |
| What modules and dependencies exist? | [Root POM](../pom.xml), affected module POMs and npm lockfile |
| How is configuration loaded and validated? | [FrameworkConfig.java](../sf-e2e-core/src/main/java/hu/ak91/sf/e2e/core/FrameworkConfig.java) and [sf-e2e.yaml](../sf-e2e.yaml) |
| What CLI flags exist? | Current CLI source and `java -jar sf-e2e-cli/target/sf-e2e.jar <command> --help` built from the current worktree |
| What preserves recipe/repository compatibility? | `CaseRecipe`, `LocatorEntry`, `LocatorStore`, `Json` and their regression checks; use the [source map](DEVELOPMENT.md#source-and-test-map) |
| What checks run in CI? | [Framework workflow](../.github/workflows/checks.yml), plugin configuration and selected test output |
| Which verification is current? | Fresh command output matched to source scope; [VERIFICATION.md](VERIFICATION.md) records dated evidence |

## Documentation maintenance

Keep one owner for each contract and link to it from related guides. Preserve dated measurements as dated measurements. Use exact source paths and runnable commands, state prerequisites and expected outcomes, and mark placeholders as consuming-project inputs. Follow [the documentation update rules](DEVELOPMENT.md#documentation-maintenance) when changing the framework.
