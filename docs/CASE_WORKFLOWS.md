# Learn once, replay without a model

The consumer workspace stays empty. Supply your own case as JSON or UTF-8 text, or ask for bounded read-only discovery. The CLI creates a private executable JSON recipe; no generated Java classes, test-module changes or recompilation are required. Configure the target and authentication as described in the [README](../README.md).

## Three commands

| Command | Input | Result | Model on later runs |
| --- | --- | --- | --- |
| `learn-case` | Your structured case or text instructions and runtime parameters | A recipe after every action/assertion succeeds; persistent locators for its visited states | None with `replay-case` |
| `discover-ui` | Start route, case ID, hard page budget | AI mapping of visible navigation pages and a recipe of verified presence checks | None with `replay-case` |
| `replay-case` | A saved recipe, matching configuration/persona, runtime parameters | Deterministic actions/assertions, locator retry/healing, evidence and attempt journal | No model client is constructed |

Learning performs the supplied actions. For a write workflow, provide its complete steps, runtime data and expected outcome. Discovery follows eligible navigation hrefs without submitting forms or clicking action buttons. Its smoke checks establish UI presence, not business-process correctness.

Page Object Model is the default for learning and replay. Controls are grouped by route context into revision-isolated page models; persistent `page`/`key` references remain the recipe contract. New IDs and CLI output filenames automatically receive `TST_` if absent; existing historical recipes keep their original IDs/checksums. The prefix counts toward the 70-character ID limit. Use the actual printed output path for replay/suite entries. See [POM and naming](PAGE_OBJECT_MODEL.md).

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

## Autonomous UI discovery

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar discover-ui --config project.local.yaml --id TST_ui-smoke --route /your/application/route --max-pages 5 --output .sf-e2e/cases/TST_ui-smoke-v1.local.json --locator-mode read-write
java -jar sf-e2e-cli/target/sf-e2e.jar replay-case --config project.local.yaml --file .sf-e2e/cases/TST_ui-smoke-v1.local.json
```

AI maps each visited page, then prioritizes destinations from currently visible links. Every visited page gets a deterministic visible-control checkpoint, preferring a heading. The recipe saves `open`/`expectVisible` steps. A one-page discovery requires no navigation decision. If no eligible destinations exist, discovery completes with the pages actually visited; it does not claim complete app coverage.

Navigation is bounded to 1..50 pages, at most 200 probed links/100 decision candidates per page, the configured base origin (plus its conventional Lightning counterpart), and explicit href routes. Record links/IDs, authentication/logout/destructive paths, scripts, downloads, inline onclick links, fragments and non-view query parameters are excluded. Permitted query keys are `filterName`, `view`, `tab`, `sort`, `order`, `page`, `pageSize`, `lang`, `language`, and `locale`. For other domains/routes, choose the actual app base origin/start route or supply an explicit case/mapping plan. Buttons, custom JavaScript navigation and hidden menus/dialogs require a supplied workflow. Action/model-call/token/overall time budgets remain hard limits.

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

Learning attempts get separate locator page keys. Failed learning may leave diagnostic mappings but never publishes a runnable recipe or changes an older recipe's keys. The recipe is saved atomically only after all steps, deterministic assertions and required completion evidence succeed. Recipes/maps survive Maven clean and are ignored when kept under `.sf-e2e`.

`replay-case` permits only the deterministic profile. It shares `HealingLocators` with Java cases, including pre-dispatch retries, alternatives/fingerprints, confidence/ambiguity checks and revision/history updates. `--locator-mode read-write` persists confident repairs. CI/read-only mode keeps repairs in memory. `--strict-locators` rejects drift. There is no AI fallback, even if a model endpoint or credential exists in the environment. An uncertain dispatched action is not repeated automatically; the case fails for review.

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
