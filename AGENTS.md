# Agent instructions

This repository is a reusable Salesforce UI automation framework. Keep framework maintenance separate from installation-specific business testing.

## Start here

1. Inspect `git status --short` and preserve existing worktree changes.
2. Read the [README agent entry point](README.md#for-ai-agents-and-maintainers).
3. Use the [documentation index](docs/README.md) to select the guide relevant to the task.
4. Follow [DEVELOPMENT.md](docs/DEVELOPMENT.md) for source locations, change recipes and verification commands.
5. Read the affected implementation and its regression checks before editing. Current source and command results establish behavior; dated audit receipts establish only their recorded scope.

## Framework invariants

- Keep `sf-e2e-tests/src/test/java` empty in framework-maintenance changes. Put internal regression checks in the implementation module that owns the behavior.
- Use synthetic HTML, mock engines/model responses and local HTTP/W3C services for framework checks. Ship no real target org, customer workflow, credential, session, record ID, package schema, persona permission or business-stage assumption.
- Consumer org configuration, locators, recipes, inputs and business cases belong to the consuming project. Built-in Salesforce protocol/route handling and synthetic contract fixtures remain valid framework responsibilities.
- Preserve Page Object Model as the default execution architecture. Resolve controls from fresh observations; do not cache live nodes across observations, contexts, personas or workers.
- Deterministic recipe replay and deterministic/regression execution must not construct a model client. Healing changes control identity, never the caller's expected result.
- Retry only a proven pre-dispatch stale-node rejection. Once a business mutation has been dispatched, retain uncertain outcomes and never blindly repeat the mutation.
- Keep Playwright calls and cleanup on the runtime's owner thread. Reuse browser processes only with fresh isolated contexts. Preserve attempt budgets and cleanup on failed initialization and repeated closure.
- Preserve exact origin/frame boundaries, identity confidence, ambiguity rejection, repository integrity and optimistic revisions. Incomplete observation cannot justify repair or absence.
- Strict agent cache replay cannot publish, evict or invalidate recordings even with read-write configured. Preserve uncertain dispatched failure codes; keep live judges independent of the acting ledger and bounded by calls, tokens and configured deadlines.
- Browser evidence masks painted sensitive content even when semantic controls omit inert/accessibility-hidden subtrees. Validate mask geometry/coverage across capture and publish only the pixel-masked buffer; uncertain coverage must withhold the PNG.
- Finalize verdicts after user teardown; class/container failures affect results and dependency release. Consumer Java execution goes through `RunCoordinator`, not an uncoordinated Jupiter launch.
- Retain every selected dataset outcome and its provenance. Required evidence, journals, browser quality and report integrity remain part of the final verdict.
- Preserve existing recipe/checksum compatibility. Capability, schema or namespace changes need explicit migration/remapping behavior and regression evidence.

## Work efficiently

- Use `rg` to find symbols and related tests. Start with the source/test matrix in [DEVELOPMENT.md](docs/DEVELOPMENT.md#source-and-test-map).
- Do not load all documentation or ignored artifacts into context. Read the task guide, then the relevant contracts and source. `.tools/`, `.reference/`, runtime maps/journals and reports are optional local evidence, not prerequisites for a fresh checkout.
- Keep changes scoped to the requested behavior. Preserve public configuration, recipe and CLI contracts unless the task requires changing them.
- Use the affected module and `-am` for focused Maven checks. Verify that the intended suite actually ran; a green build with zero selected checks is insufficient.
- Use `verify -Pchecks` for browser/Failsafe acceptance. `test` omits browser harnesses; `package -DskipTests` builds an artifact without validating it.
- For documentation-only changes, run the documentation validator and `git diff --check`. Run example/API checks when the examples change; repeat the full Java suite when executable behavior or unresolved concerns justify it.
- Use the Maven Wrapper and checked-in dependency/tool pins. The checked build uses JDK 27 and emits Java 21 bytecode; see [DEPENDENCIES.md](docs/DEPENDENCIES.md). Do not depend on a pre-existing `.tools` JDK path.
- Edit sources, POMs, templates and scripts. Maven `target/`, the shaded CLI and the generated Allure engine archive are build outputs.

## Documentation and handoff

Public documentation, framework messages and configuration comments use English. Localized app/test fixtures may use other languages.

When behavior changes, update the owning guide and any affected README/configuration examples. Explain the trigger, resulting behavior, compatibility implications and exact verification scope. New guides must appear in [docs/README.md](docs/README.md); maintain local links and heading anchors.

Public root/docs Markdown is bundled with the CLI. When adding a guide, expose an explicit safe topic in `GuideCommand` and keep `GuideCommandTest`'s all-topic read check passing. `guide --list` works without a checkout/config; the [reference audit](docs/REFERENCE_PRACTICES.md) records the pinned upstream comparison and explicit feature gaps.

A handoff should identify the changed files, completed checks, remaining work and any environment-dependent limitation. Label synthetic/local checks, historical receipts and live target execution accurately. Do not claim hosted CI, private org compatibility, production throughput or paid-provider savings from local fixture results.
