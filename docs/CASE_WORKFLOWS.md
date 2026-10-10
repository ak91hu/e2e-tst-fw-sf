# Learn once, replay without a model

[Documentation index](README.md) · [Project README](../README.md) · [Agent instructions](../AGENTS.md) · [Development guide](DEVELOPMENT.md)

The consumer workspace stays empty. Supply your own case as JSON or UTF-8 text, or ask for bounded read-only discovery. The CLI creates a private executable JSON recipe; no generated Java classes, test-module changes or recompilation are required. Configure the target and authentication as described in the [README](../README.md). [Autonomous workflows](AUTONOMOUS_WORKFLOWS.md) owns consumer-chartered workflow exploration/repro bundles, local MCP, generated verification skills, encrypted sessions and dependency-safe `--shard` / `--last-failed` selection.

## Contents

- [Case commands](#case-commands)
- [Maintain existing tests](#maintain-existing-tests)
- [Review a plan before executing](#review-a-plan-before-executing)
- [A supplied structured case](#a-supplied-structured-case)
- [A supplied text case](#a-supplied-text-case)
- [Instruction order and implemented HTML design](#instruction-order-and-implemented-html-design)
- [Autonomous UI discovery](#autonomous-ui-discovery)
- [Interpret discovery scope and continue](#interpret-discovery-scope-and-continue)
- [Resolve incomplete case input](#resolve-incomplete-case-input)
- [Supported steps](#supported-steps)
- [Persistence, healing and evidence](#persistence-healing-and-evidence)
- [Dataset suites and quality gates](#dataset-suites-and-quality-gates)
- [Existing external environment / subscription](#existing-external-environment-subscription)

## Case commands

| Command | Input | Result | Model on later runs |
| --- | --- | --- | --- |
| `plan-case` | Text instructions, explicit start route and optional runtime parameters | An editable specification and separate private parameters; no target session or UI execution | Planning uses AI; review the specification before learning |
| `validate-case` | A structured specification and its runtime parameters | Offline schema, assertion and parameter/value validation; no configuration or model | None; this does not verify target UI or business correctness |
| `learn-case` | Your structured case or text instructions and runtime parameters | A recipe after every action/assertion succeeds; persistent locators for its visited states | None with `replay-case` |
| `discover-ui` | Start route, case ID, hard page budget | AI mapping of visible navigation pages and a recipe of verified presence checks | None with `replay-case` |
| `replay-case` | A saved recipe, matching configuration/persona, runtime parameters | Deterministic actions/assertions, locator retry/healing, evidence and attempt journal | No model client is constructed |
| `maintenance-plan` | A checksummed baseline recipe, optional additive changes and explicit related recipes | Offline retained/inserted step plan, required parameter names and potential shared-control dependencies | No configuration, browser, model or writes |
| `maintain-case` | The same baseline, optional reviewed insertions, target configuration and runtime values | One verified merged workflow, a new versioned recipe and HTML maintenance design | Locator-only maintenance constructs no model; insertions use bounded mapping/repository-first reuse |

Learning performs the supplied actions. For a write workflow, provide its complete steps, runtime data and expected outcome. Discovery follows eligible navigation hrefs without submitting forms or clicking action buttons. Its smoke checks establish UI presence, not business-process correctness.

Page Object Model is the default for learning and replay. Controls are grouped by route context into revision-isolated page models; persistent `page`/`key` references remain the recipe contract. New IDs and CLI output filenames automatically receive `TST_` if absent; existing historical recipes keep their original IDs/checksums. The prefix counts toward the 70-character ID limit. Use the actual printed output path for replay/suite entries. See [POM and naming](PAGE_OBJECT_MODEL.md).

## Maintain existing tests

Maintenance distinguishes layout drift from changed business intent. A moved element or changed XPath often needs no update because recipes use semantic controls, not raw XPath strings. A renamed label with sufficient independent identity evidence can repair its shared `page`/`key` definition. Exact origin, route/query, frame/scope, engine capability, completeness, confidence and ambiguity checks still apply. Missing/conflicting identity evidence stops maintenance; there is no automatic confidence reduction, arbitrary XPath substitution or replacement of expected outcomes.

Start with a checksummed recipe produced by learning. This offline command prints its baseline checksum, retained steps and required parameter names without opening the target:

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar maintenance-plan --file .sf-e2e/cases/TST_my-flow-v1.local.json --related-recipe .sf-e2e/cases/TST_related.local.json
```

`--related-recipe` is repeatable and optional, with at most 200 supplied recipes. The plan lists only supplied recipes in the same namespace and engine capability that share existing control references. This is potential dependency information: those cases are not executed, references need not actually drift, and unlisted recipes or handwritten Java tests are outside the analysis. It does not change a suite manifest or claim complete application impact coverage. Optional `--params` validates values without printing them.

For locator-only maintenance, omit `--changes`:

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar maintain-case --config project.local.yaml --file .sf-e2e/cases/TST_my-flow-v1.local.json --params values.local.json --output .sf-e2e/cases/TST_my-flow-v2.local.json --locator-mode read-write
```

This selects `deterministic`, constructs no model client and executes the existing workflow once through its POM references. Confident repairs update the shared locator repository, including hashed intent associations for the repaired key, rather than creating duplicate definitions. Other recipes using that key receive the repaired definition on their next fresh resolution. Existing recipe files, historical IDs and expected-result references remain unchanged. When no repair is necessary, the new recipe can have the same checksum as the baseline. Use fresh strict/read-only replay afterward to verify the persisted map without further healing.

### Add a field without relearning the case

An extra field does not authorize an inferred value, additional save or changed expected result. Supply explicit operations and private runtime values. Save a private `changes.local.json`, replacing the checksum with `baselineChecksum` from `maintenance-plan`:

```json
{
  "schemaVersion": 1,
  "baselineChecksum": "REPLACE_WITH_THE_BASELINE_RECIPE_SHA256",
  "insertions": [
    {
      "beforeStep": 2,
      "steps": [
        {"kind": "fill", "instruction": "New field label in your application", "valueRef": "newField"},
        {"kind": "expectValue", "instruction": "New field label in your application", "valueRef": "expectedNewField"}
      ]
    }
  ]
}
```

`beforeStep` is a **1-based position in the unchanged baseline**, not the growing merged case. In a fill/save/check recipe, position 2 places the new field before Save. Choose the actual position in your case; framework fixtures supply no business-stage assumption. Insertions must have unique ascending positions and retain their supplied order. Position `baseline step count + 1` appends steps, which must end in a caller-supplied assertion. The merged case is limited to 200 steps. Changes can only insert steps: unknown replacement/removal/value fields are rejected, and a mismatched baseline checksum fails before authentication. A deliberate change to existing actions, routes, controls or oracle references requires a separately reviewed full specification and normal learning.

Add `newField` and `expectedNewField` explicitly to the private parameter file, alongside all existing parameters. Use the actual observed field label and named `scope` when needed. Then review and execute:

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar maintenance-plan --file .sf-e2e/cases/TST_my-flow-v1.local.json --changes changes.local.json --params values.local.json --related-recipe .sf-e2e/cases/TST_related.local.json
java -jar sf-e2e-cli/target/sf-e2e.jar maintain-case --config project.local.yaml --file .sf-e2e/cases/TST_my-flow-v1.local.json --changes changes.local.json --params values.local.json --related-recipe .sf-e2e/cases/TST_related.local.json --output .sf-e2e/cases/TST_my-flow-v2.local.json --locator-mode read-write
java -jar sf-e2e-cli/target/sf-e2e.jar replay-case --config project.local.yaml --file .sf-e2e/cases/TST_my-flow-v2.local.json --params values.local.json --strict-locators --locator-mode read-only
```

Insertions select the `mapping` profile and require its model credential, even when repository reuse avoids all target calls. Every retained step executes directly through its original recipe reference, without target analysis; every inserted visible target checks the repository first. Only unknown targets need bounded model interpretation. Retained steps are excluded from paid target batches. Newly supplied negative assertions still require the same target to have been observed in the current learning workflow; historical knowledge alone cannot prove absence.

The single merged execution retains original order, action kinds, locator keys, parameter references and assertions. Original and inserted expected values are read only from the supplied parameters; maintenance never copies observed values into them. A dispatched uncertain mutation stops the attempt without automatic retry. The HTML `test-design.html` marks retained baseline positions and new supplied steps, baseline/changes checksums, potential shared dependencies, actual before/after repairs and outcomes. Failed attempts retain their design, evidence and journal when reporting can complete. Related cases have no new verdict merely because they share a repaired control. Historical recipe IDs and their report identity are preserved, including older naming conventions.

New recipe publication follows successful browser cleanup and final report/evidence verification; existing output paths are protected by the same publication lock as learning. Shared repairs and new locator definitions may already persist during an ultimately failed attempt, and business actions may already have committed. Maintenance supplies no transaction rollback and never automatically reruns the case to compensate. Review the journal before any explicit rerun, validate each relevant related case with caller-owned data, and update consumer suite paths through review. Use version control for reviewed recipes/maps and preserve locator history; this command does not overwrite or migrate old recipes or engine capabilities.

## Review a plan before executing

Use this route when a written case needs AI translation and you want to inspect the actions, scopes and expected results before the target is opened. `plan-case` uses the configured mapping model and bounded call/token/time policy, but constructs no browser, authenticates no target and writes no locators. It needs the project YAML and model credential; it does not require `--locator-mode read-write`. Target configuration fields must still satisfy the YAML schema.

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar plan-case --config project.local.yaml --instructions flow.local.txt --id TST_my-flow --route /your/application/route --params values.local.json --output .sf-e2e/plans/TST_my-flow-v1.local.json
java -jar sf-e2e-cli/target/sf-e2e.jar validate-case --spec .sf-e2e/plans/TST_my-flow-v1.local.json --params .sf-e2e/plans/TST_my-flow-v1.local.json.params.local.json
java -jar sf-e2e-cli/target/sf-e2e.jar learn-case --config project.local.yaml --spec .sf-e2e/plans/TST_my-flow-v1.local.json --params .sf-e2e/plans/TST_my-flow-v1.local.json.params.local.json --output .sf-e2e/cases/TST_my-flow-v1.local.json --locator-mode read-write
```

After the first command, review/edit the specification before continuing. It contains `{id,startRoute,steps}` with target instructions and parameter references. Every supplied parameter plus any validated verbatim literal goes into `<spec filename>.params.local.json`; if there are no parameters, no sidecar is written and omit `--params` in subsequent commands. The output filename receives `TST_` when absent. Existing output/sidecar files are preserved. Use the printed path and a new versioned path for replanning.

A plan is **unverified intent**, not a checksummed runnable recipe. Do not feed it to `replay-case` or `run-suite`. `validate-case` needs only the specification and optional parameters, so it works without a YAML file, model endpoint, target session or browser installation. Its JSON response includes step/assertion counts, required parameter names and `uiVerified:false`, without echoing values. It validates supported kinds/routes, a final assertion, required references and operation values, including booleans, keys, counts and structured widget inputs. It cannot establish that a label is unique, the target exists, the application behaves correctly or an absence assertion is observable.

Only successful `learn-case` execution creates a verified recipe bound to the target/persona. After reviewing a plan, structured learning avoids another text-planning call; target resolution still needs AI. Both direct text learning and separate planning use the same protocol, literal checks and bounded format repair. Neither invents missing expected results or runtime data.

## A supplied structured case

Create your private `flow.local.json` with the target app's routes and labels. This format illustration is not a bundled Salesforce business case:

```json
{
  "id": "TST_my-flow",
  "startRoute": "/your/application/route",
  "steps": [
    {"kind": "fill", "instruction": "The field labelled Reference", "valueRef": "reference"},
    {"kind": "expectValue", "instruction": "The field labelled Reference", "valueRef": "expectedReference"}
  ]
}
```

Supply input/expected values separately in `values.local.json`:

```json
{"reference": "RUN-20261006-001", "expectedReference": "RUN-20261006-001"}
```

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar learn-case --config project.local.yaml --spec flow.local.json --params values.local.json --output .sf-e2e/cases/TST_my-flow-v1.local.json --locator-mode read-write
java -jar sf-e2e-cli/target/sf-e2e.jar replay-case --config project.local.yaml --file .sf-e2e/cases/TST_my-flow-v1.local.json --params values.local.json --locator-mode read-write
```

The first command requires the model configuration/credential. The second requires only the target's authenticated session and runtime values. Change `values.local.json` for another run; the recipe stores references rather than these values. Make write cases repeatable through explicit setup/navigation and suitable unique inputs; replay does not roll back data or create hidden setup records. Existing output files are preserved: use a new versioned output path when learning again.

## A supplied text case

Write your instructions in `flow.local.txt`, in the language you use for the target app. Include explicit actions and an expected result. You can supply literal values directly:

```text
Enter "RUN-20261006-001" into the field labelled Reference.
Verify that this field's value is exactly "RUN-20261006-001".
```

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar learn-case --config project.local.yaml --instructions flow.local.txt --id TST_my-flow --route /your/application/route --output .sf-e2e/cases/TST_my-flow-v2.local.json --locator-mode read-write
java -jar sf-e2e-cli/target/sf-e2e.jar replay-case --config project.local.yaml --file .sf-e2e/cases/TST_my-flow-v2.local.json --params .sf-e2e/cases/TST_my-flow-v2.local.json.params.local.json
```

Literal inputs/expectations must occur verbatim in the supplied text. AI returns them separately from the plan, and validation rejects invented values or redefinitions of caller parameters. After verified learning, the command saves them in `<recipe filename>.params.local.json` and prints its path. The recipe itself still contains only references. Empty/computed values require explicit runtime parameters; missing expectations are never inferred from the observed UI.

You can also identify values by parameter reference:

```text
Enter ${reference} into the field labelled Reference.
Verify that this field's value is exactly ${expectedReference}.
```

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar learn-case --config project.local.yaml --instructions flow.local.txt --id TST_my-flow --route /your/application/route --params values.local.json --output .sf-e2e/cases/TST_my-flow-v2.local.json --locator-mode read-write
```

AI translates the text into the supported step format, then resolves each target from the actual UI. Parameter names must refer to supplied values or validated verbatim literals. Missing/ambiguous targets, unsupported instructions or missing inputs stop learning. Use `--spec` when you want exact steps/scopes. Login is framework authentication, not a case step. The resulting recipe can be inspected as JSON; its page/key references can be inspected with `locators list` and `locators history`.

## Instruction order and implemented HTML design

For text designs, write one explicit instruction per nonblank line, in execution order, including intermediate assertions and the final expected result. Both `plan-case` and direct text learning send these ordered lines to the planner. Every generated step must identify its 1-based source instruction; several consecutive steps may implement one line. Missing, out-of-range or reordered references are rejected before case actions. An omitted line produces `CASE_INSTRUCTION_MISMATCH`; unsupported intent must remain blocked. There are at most 200 nonblank lines and 200 resulting steps. Blank lines are ignored. Multiline headings/context are also instruction lines, so include relevant context with its action or supply an exact structured `--spec`.

This validates ordered line coverage, not natural-language semantic equivalence. A model can still misunderstand an action or claim an incorrect source reference. Review translated targets, scopes and independent assertions through `plan-case`; use `--spec` for caller-controlled exact operations. Keep multiple independent actions on separate lines so omissions inside a compound sentence are easier to detect. No model judge replaces the caller's expected outcome.

The learner establishes its own `CaseSpec` before case actions, executes it in order and stops at the first unsuccessful step. After learning, the command prints **Implemented test design** with the run-owned `test-design.html` path. Allure also includes an **Implemented test design** HTML attachment. The standalone document contains:

- Case ID, start route and final attempt status/code.
- Every planned operation, target, scope and input/expected-result parameter reference, in order.
- Source instruction numbers for direct text learning; structured learning retains the supplied step order.
- Completed implementation page/key/route references, with `COMPLETED`, `STARTED — NOT COMPLETED` and `NOT EXECUTED` outcomes.

The document is retained for failed learning after the plan is established, including later unexecuted steps. A started unfinished mutation can have an uncertain dispatched outcome; inspect the permanent journal before any explicit rerun. Completed steps cannot turn a failed evidence, quality or cleanup verdict into a passed attempt. Authentication/planning failures before `case-plan-ready` have no implemented design. Discovery, deterministic replay and Java consumer tests do not reconstruct absent authored intent into this document.

The renderer consumes redacted events, escapes application text, uses no scripts or external resources and omits runtime parameter maps. Original free-text instructions/literal values are not copied into the HTML; references remain visible. Targets/labels may still contain consumer business data, so protect the report as other run evidence. `case-report.json` retains the HTML checksum, and `report verify` checks the standalone document and its Allure attachment copy. `report build` regenerates them from retained events without a model or target session. Existing reports without this additive receipt and existing spec/recipe schemas/checksums remain compatible. Custom text-planning model fixtures must return `sourceInstruction` in each model step; this metadata is removed before specification serialization.

For usability, the recommended sequence is `plan-case`, review/edit the intent, `validate-case`, `learn-case --spec`, inspect the HTML design, then `replay-case` or `run-suite`. Learning produces an executable JSON recipe using Page Object Model; it does not generate Java test classes. Successful local framework fixtures do not establish compatibility with a consumer's target application.

## Autonomous UI discovery

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar discover-ui --config project.local.yaml --id TST_ui-smoke --route /your/application/route --max-pages 5 --output .sf-e2e/cases/TST_ui-smoke-v1.local.json --locator-mode read-write
java -jar sf-e2e-cli/target/sf-e2e.jar replay-case --config project.local.yaml --file .sf-e2e/cases/TST_ui-smoke-v1.local.json
```

AI maps each visited page, then prioritizes destinations from currently visible links. Every visited page gets a deterministic visible-control checkpoint, preferring a heading. The recipe saves `open`/`expectVisible` steps. A one-page discovery requires no navigation decision. If no eligible destinations exist, discovery completes with the pages actually visited; it does not claim complete app coverage.

Navigation is bounded to 1..50 pages, at most 200 probed links/100 decision candidates per page, the configured base origin (plus its conventional Lightning counterpart), and explicit href routes. Record links/IDs, authentication/logout/destructive paths, scripts, downloads, inline onclick links, fragments and non-view query parameters are excluded. Permitted query keys are `filterName`, `view`, `tab`, `sort`, `order`, `page`, `pageSize`, `lang`, `language`, and `locale`. For other domains/routes, choose the actual app base origin/start route or supply an explicit case/mapping plan. Buttons, custom JavaScript navigation and hidden menus/dialogs require a supplied workflow. Action/model-call/token/overall time budgets remain hard limits.

## Interpret discovery scope and continue

A successful discovery saves its structured `discovery` summary in both `case-report.json` and the permanent attempt journal, attaches the completion event to Allure's redacted event log and prints the summary. The summary is evidence of navigation presence; `applicationCoverageComplete` is always `false`. It contains visited routes, each verified checkpoint's page/key, model-call count, page budget, known remaining-route count/list and excluded business scope. It is not an org-wide percentage or a complete inventory.

| `stopReason` | Meaning | Next action |
| --- | --- | --- |
| `PAGE_LIMIT` | The hard page cap was reached with known unvisited eligible routes | Increase `--max-pages` within 1..50, or start a new discovery at a listed remaining route with a new ID/output |
| `MODEL_SELECTION_STOPPED` | The model selected no further queued destinations, although known eligible routes remain | Review the remaining list and explicitly start from a relevant route; use a supplied case for hidden/action-driven navigation |
| `NO_MORE_ELIGIBLE_ROUTES` | No known eligible destination remains unvisited in the observed surface | Review other personas, forms, dialogs and business outcomes as separate supplied workflows |

Even the last visited page is probed for eligible hrefs before reporting the frontier. `pendingPages` counts only queued model-selected destinations; `remainingRouteCount` includes known destinations the model did not select. Thus a one-page limit can have zero queued pages and still list remaining work. Remaining routes are sorted, capped at 200 displayed entries and marked with `remainingRoutesTruncated`; `candidateLimitReached` identifies a navigation decision with more than 100 unique candidates. The engine's own bounded probes still apply. Routes excluded by the engine are not added to the remaining list. Duplicate hrefs consume one decision candidate.

The presence recipe can pass even when the traversal stops early; this means its saved checkpoints passed, not that the application was fully explored. Model/action/token/time exhaustion or incomplete observations remain failures and do not publish a passing recipe. To continue discovery, use a new output path and inspect the new summary; there is no hidden resume state or automatic merging of recipes.

## Resolve incomplete case input

| Symptom | Correction |
| --- | --- |
| `CASE_PARAMETER_REQUIRED` names missing references | Supply those keys in a private string-valued JSON parameter file; include explicit expected values |
| A final click/save is rejected | Add the caller's observable expected result as a supported assertion |
| `CASE_LEARNING_BLOCKED` during planning | Specify actions, target labels/scopes, inputs and expected results explicitly; use `--spec` for exact intent |
| Offline validation passes but learning reports an ambiguous target | Use a named dialog/form/region scope and a precise instruction; offline validation cannot inspect the UI |
| `NEGATIVE_ASSERTION_UNPROVABLE` | First identify the same visible target with stable `testId`/`apiName`; an invented absent control cannot establish success |
| `CASE_RECIPE_EXISTS` | Preserve the previous artifact and choose a new versioned specification/recipe output, accounting for the automatic `TST_` prefix and parameter sidecar |
| `CASE_OUTPUT_BUSY` | Another publisher holds this recipe output; inspect the current journal/report and preserve the published artifact before any explicit rerun |
| Replay rejects a plan or a different persona's recipe | Learn the reviewed specification for that target/persona, then replay the printed verified recipe path |

Keep setup, uniqueness and cleanup requirements explicit in the consuming project. Discovery does not infer business expectations, create records or establish role coverage. No target org workflow or generated business case belongs in the framework distribution.

## Supported steps

| Kind | Fields | Deterministic behavior |
| --- | --- | --- |
| `open` | `route` | Opens an app-relative route |
| `click` | `instruction`, optional `scope` | Clicks the learned unique target |
| `fill`, `select` | `instruction`, `valueRef`, optional `scope` | Supplies runtime text or the exact visible option label |
| `setChecked` | `instruction`, `valueRef`, optional `scope` | Requires string `true` or `false` |
| `press` | `instruction`, `valueRef`, optional `scope` | Allows Enter, Tab, Escape, arrow keys, Home, End, Space |
| `expectVisible` | `instruction`, optional `scope` | Requires a unique observable target |
| `expectEnabled`, `expectDisabled` | `instruction`, optional `scope` | Polls the actual disabled state |
| `expectHidden` | Exact earlier target instruction/scope | Requires a previously mapped stable `testId`/`apiName`, complete scope and three consecutive absence observations |
| `expectCount` | `instruction`, `valueRef`, optional `scope` | Exact count 0..10000 across three observations; zero needs the same anchored absence contract |
| `expectText`, `expectValue` | `instruction`, `valueRef`, optional `scope` | Polls for exact text/value supplied by the caller |
| `expectChecked` | `instruction`, `valueRef`, optional `scope` | Polls for the caller's checked state |
| `expectRoute` | `route` | Polls for the exact app-relative path/query/fragment |

Scope defaults to `page`; use named dialog/region/group/form scopes for duplicates and asynchronous dialogs. For example, `"scope":"dialog:Your dialog name"`. `open`/`expectRoute` have no instruction or value fields. All cases must end in an assertion; a save/click alone cannot produce a passing recipe. Text and structured learning share the same restrictions.

Count assertions deliberately do not heal the semantic description to make the expected count true. During learning, a positive count may identify multiple controls with the same exact semantic description in the learned frame/scope. For a zero-count or `expectHidden` step, reuse an earlier visible target's exact instruction/scope; the stored identity is reused rather than inventing an absent target. A renamed or moved anchored visible target prevents an absence pass. Missing identity or incomplete coverage is an explicit failure.

Recognized record links use `recordNameRef` pointing at a runtime parameter. Only that record's link is eligible, and its name is projected as a placeholder and stored as `${recordName}`. Its business name is not stored in the recipe/map. A missing record never heals to another record. Other accessible names may contain business data, so keep maps/artifacts private when appropriate.

## Persistence, healing and evidence

Recipes use schema version 1 and a SHA-256 integrity checksum. A namespace hash binds them to the configured project/org/persona/user/UI settings; engine capability changes require learning again. To use the same intent on another Salesforce installation, learn the same supplied specification against that installation. Source code and input format remain general; UI maps are deliberately deployment-specific runtime data.

Every learning step first searches existing locator definitions and validated hashed instruction associations in the current namespace. Proven current controls retain their existing page/key without another target-identification call or locator write; paid batches exclude known targets. Unknown wording may require interpretation, followed by a second existing-definition check to avoid recording the same control twice. See [repository reuse and its limits](AI_WORKFLOW.md#smaller-requests-bounded-batches-and-unchanged-maps). New or changed identities get separate attempt-isolated locator page keys. Failed learning may leave diagnostic mappings but never publishes a runnable recipe or changes an older recipe's keys. CLI recipe publication follows successful steps, deterministic assertions, required completion evidence, browser/session cleanup and report finalization. A cleanup or report failure cannot publish the recipe or extracted-parameter sidecar; finalization failures update the permanent journal and retain failed report data without repeating application actions. Recipes/maps and their optional `.target-index.json` association sidecar survive Maven clean and are ignored when kept under `.sf-e2e`.

`replay-case` permits only the deterministic profile. It shares `HealingLocators` with Java cases, including pre-dispatch retries, alternatives/fingerprints, confidence/ambiguity checks and revision/history updates. `--locator-mode read-write` persists confident repairs. CI/read-only mode keeps repairs in memory. `--strict-locators` rejects drift. There is no AI fallback, even if a model endpoint or credential exists in the environment. An uncertain dispatched action is not repeated automatically; the case fails for review.

CLI publication uses an adjacent `<recipe filename>.publish.lock` to serialize same-output writers and rechecks existing recipe/parameter files while locked. An empty lock file can remain after release; file presence alone does not mean a publisher is active. A secondary journal/report failure retains the original workflow failure code and is added as suppressed diagnostics; it cannot justify repeating a dispatched action.

Each command uses the same per-org local execution lock as the JUnit coordinator. A permanent `.sf-e2e-data/case-attempts/<attempt>.json` records status, model-call count, last step/event and evidence ownership before/after execution. `report/<UTC timestamp>/case-report.json`, redacted events and checksummed artifact manifests provide diagnostics. The folder preserves the run-start instant with nine fractional digits; exact timestamp collisions receive a unique suffix without reusing previous evidence. Relative report roots resolve against the config directory. Masked start/completion, completed-step and failure screenshots are enabled by default. The bundled Allure engine automatically generates a standalone `index.html`, with screenshots attached to their steps, actual verdicts, model calls and healing/retry events. `report build/verify` supports these file workflows and JUnit runs. See [Allure reporting](ALLURE_REPORTING.md). The generic attempt journal records execution; it does not infer created Salesforce object IDs or replace application-specific record registration.

Framework model requests for target discovery omit field values and expected values. The model receives visible target metadata and the supplied intent. Do not place credentials in instructions/ordinary params; use the configured authentication references. Parameter files remain local, and no recipes/cases are shipped by default.

## Dataset suites and quality gates

`validate-suite` preflights a private manifest without a browser or model. `run-suite` executes its selected recipes/parameter files sequentially in fresh browser contexts and generates one timestamped Allure report. Dataset IDs, personas and optional tags distinguish invocations; paths resolve relative to the manifest. Failed/blocked/skipped selected rows cannot produce a passing aggregate. See the [README suite examples](../README.md#data-driven-regression-suites).

Authenticated execution collects bounded browser/console/HTTP/transport issues and applies enabled quality gates before accepting completion. A browser defect can fail a UI presence check that otherwise passed. Diagnostics, reviewed waivers, locator drift and assertion events appear in Allure and `quality-summary.json`. See [quality gates](../README.md#quality-gates-and-defect-detection).

`--coverage private/coverage.json --changes private/changes.json` enables reviewed change-impact selection on `validate-suite` and `run-suite`. Transitive `after` prerequisites are included; unknown/global changes select the full suite. Manual tag/dataset filters cannot narrow impact selection. Empty changes create no passing execution report. Offline `plan-impact` exposes the decision and checksums before running. See [efficiency workflows](EFFICIENCY_WORKFLOWS.md).

## Existing external environment / subscription

The case schema also supports opt-in file/page/dialog and generic widget operations, including targetless `switchPage`. Structured parameters are validated before dispatch and remain runtime references in recipes. See [the operation/parameter table](ADVANCED_CAPABILITIES.md#files-pages-dialogs-and-widgets). `run-suite` can schedule dependency/resource-aware isolated workers while keeping saved replay model-free; see [worker contracts](ADVANCED_CAPABILITIES.md#isolated-workers-and-browser-reuse).

The optional launcher supports `map-ui`, `learn-case` and `discover-ui` with an existing installed ChatGPT provider:

```powershell
node scripts/run-with-env.mjs --env-file C:\your-project\.env --chatgpt-project C:\your-project -- learn-case --config project.local.yaml --spec flow.local.json --params values.local.json --output .sf-e2e/cases/TST_my-flow-v1.local.json --locator-mode read-write
node scripts/run-with-env.mjs --env-file C:\your-project\.env -- replay-case --config project.local.yaml --file .sf-e2e/cases/TST_my-flow-v1.local.json --params values.local.json
```

Replay does not start a subscription adapter. Direct Java replay needs no model credentials. Its automatic Allure 3 reporting requires Node.js 26.10.0 or later. Use `--java <Java 21 executable>` with the launcher if Java 21 is not the default runtime.

The workflow applies the [e2e principles of verified action replay, deterministic checks and redacted evidence](https://e2e.tester.army/docs). Full-case replay here always executes saved deterministic assertions; live AI judgments are not converted into assertions by caching their verdict.
