# Backend, execution and quality capabilities

These opt-in capabilities implement the ten development tracks in [the implementation ledger](ROADMAP_IMPLEMENTATION.md). They extend the generic framework; object names, records, application flows, expected values, tools and target coverage remain consumer inputs. There are no shipped Salesforce business cases.

## REST oracle and metadata regression

Use `SalesforceApi` with a separately authorized REST token, or `sf.api(policy)` in a consumer JUnit case. The context helper obtains a JWT grant for the current persona and verifies the returned instance URL. Browser storage state alone does not authorize REST; provision a separate token and construct the client explicitly for that installation. API versions are explicit rather than inferred from the latest Salesforce release.

```java
var policy = new hu.ak91.sf.e2e.salesforce.api.SalesforceApi.Policy(
    "65.0", 20, 5000, 1048576, 100);
var api = sf.api(policy);
var values = api.record(objectApiName, ownedRecord.id(),
    java.util.List.of("Name"), deadline);
api.expectRecord(objectApiName, ownedRecord.id(),
    java.util.Map.of("Name", suppliedExpectedName),
    java.time.Duration.ofMillis(250), deadline);
```

The version is an example, not a promise that every org supports it. Limits respectively bound query pages, records, response bytes and requests. `record` requests/verifies `Id` plus every supplied field. `query` accepts an explicit SOQL SELECT, follows only same-version relative query continuations, detects loops and never silently truncates a dataset. Permission and transport failures fail immediately; eventual assertions retry only fresh reads. An expired eventual assertion yields `API_ASSERTION_FAILED`. No mutation is repeated by the oracle.

Request timeouts remain active until bounded body completion. `usage()` records physical requests, response bytes and the Salesforce API-usage header when present; missing org usage remains `-1`, not zero. Exported request events contain method, duration, outcome and usage, with no token, SOQL, values, URL query or response body.

`schemaFingerprints(objectNames, deadline)` computes stable, ordered field-metadata fingerprints. Review and retain the returned map, then use `expectSchema(reviewed, deadline)` to reject schema/permission drift. A new expected fingerprint requires an explicit review; CI must not regenerate the expected value immediately before comparison. [Salesforce describe](https://developer.salesforce.com/docs/platform/api-rest/guide/resources-sobject-describe.html) and [SOQL query](https://developer.salesforce.com/docs/platform/api-rest/guide/resources-query.html) define the underlying contracts.

## Owned API fixtures

`sf.fixtures(api)` or `new OwnedFixtures(api, registry)` prepares explicitly supplied standard/custom object data. Use `uniqueName(label, maximumLength)` for attempt-specific random names; retain the E2E ownership prefix. The provider validates object/field create permissions, required fields, supported metadata types/lengths, restricted picklists and owned parent relationships before dispatch.

```java
var fixtures = sf.fixtures(api);
var name = fixtures.uniqueName("Fixture", 80);
var owned = fixtures.create(objectApiName, "Name",
    java.util.Map.of("Name", name), null, null,
    hu.ak91.sf.e2e.core.Persona.DEFAULT, deadline);
```

Values are supplied by the consumer; the provider does not invent required fields or bypass validations. For a child, supply the owned parent and explicit relationship field. The field's `referenceTo` metadata must match the parent's object type. A bounded `createAll(List<Seed>, deadline)` exposes each record's passed/failed/skipped result; parents precede children, a failed parent skips its children, and an uncertain write stops later writes. Independent validation failures can leave other supplied records successful: inspect **every** result. This implementation uses sequential single-record REST creates, not a Composite rollback promise.

Save intent is journaled before the one-shot POST. An unverified commit remains `SAVE_UNCERTAIN`. Reusing its name is rejected. `reconcile(record, exactUniqueField, deadline)` requires exactly one matching persisted identity and updates that existing journal; absence/duplicates do not authorize another create. An explicit server rejection is conservatively retained for review. Records and journals are permanent. There is no API deletion, org reset or automatic rollback. Metadata validation cannot replace server-side rules, triggers, sharing or a supplied business assertion.

## Isolated workers and browser reuse

`execution.workers: 1..8` controls `run-suite`. The scheduler validates the complete dependency graph before creating a report or browser. Each worker uses its own Playwright Java thread/runtime/browser process; each dataset creates a fresh authenticated context. Contexts, DOM state, cookies/local storage, case parameters, event lists and attempts are not shared. Browser reuse amortizes startup inside a run; a closed scope does not leave a global browser pool.

Each dataset can declare:

```json
{
  "id": "consumer-row",
  "recipe": "private/recipe.json",
  "params": "private/values.json",
  "persona": "reviewer",
  "tags": ["regression"],
  "after": ["consumer-setup"],
  "resources": ["record:shared-fixture", "org:configuration"]
}
```

This is a manifest-row shape, not a shipped case. All dependencies must be present in the selected subset; missing dependencies/cycles fail preflight. Failed dependencies skip downstream rows. `--fail-fast` prevents pending rows from dispatching; already running rows finish with their own verdicts. Cancellation stops pending work; dispatched actions are never replayed. Results retain manifest order irrespective of completion order.

The scheduler locks declared resources and authorized identity inputs. Cross-process shared org leases coexist only with distinct exclusive identity leases; an older/exclusive org run blocks them. Explicitly lock shared records or org settings across otherwise independent identities. Multiple rows for one identity are intentionally serialized. Parallelism therefore depends on truly independent personas/data, not just increasing the worker number. `run` for Java/JUnit cases keeps its dependency-aware single owner thread and rejects `workers > 1`; its public coordinator also reuses a scoped browser process across isolated case contexts. Playwright's [Java threading contract](https://playwright.dev/java/docs/multithreading) remains enforced.

## Files, pages, dialogs and widgets

New explicit case/recipe kinds use runtime `valueRef` parameters. Target instructions are learned and stored through the same semantic locator mechanism; later replay creates no provider.

| Kind | Runtime parameter | Contract |
| --- | --- | --- |
| `lookup` | JSON string with `query`, `option` | Type once; wait for exactly one visible option in the observed frame and control-owned listbox |
| `multiSelect` | JSON label array | Native multiple select or ARIA multi-select with explicit option state; unique labels, no hidden virtual selected-set guessing |
| `richText` | Text | Fill the learned contenteditable/textbox; supply a separate expected outcome |
| `workspaceTab` | None | Activate the exact observed `tab`; use route/text/state assertions afterward |
| `gridFind` | JSON string with `row`, `maxScrolls` | Find one exact visible row within 0..100 scroll windows; missing/duplicate rows fail; visible rows never imply total dataset count |
| `upload` | Relative input-file path | Native input or file chooser; configured input root, real-path containment, checksum and 64 MiB bound |
| `download` | JSON string with `sha256`, `text` | One click; owned private output; exact checksum and optional exact UTF-8 text assertion, 64 MiB bound |
| `clickPopup` | Expected relative route | One click; approved popup origin/route; switch to its fresh page identity |
| `switchPage` | `main` or an existing runtime handle | Targetless step; explicit identity, closed/unknown pages fail |
| `clickDialog` | JSON string with `type`, `message`, `decision`, `prompt` | One click; exact native dialog type/message; accept/dismiss explicitly; mismatches fail and are dismissed |

Examples of **parameter values**, not AI instructions:

```json
{
  "lookupChoice": "{\"query\":\"supplied search\",\"option\":\"Exact supplied result\"}",
  "multipleChoices": "[\"Supplied A\",\"Supplied B\"]",
  "gridRow": "{\"row\":\"Exact supplied row\",\"maxScrolls\":\"20\"}",
  "confirmation": "{\"type\":\"confirm\",\"message\":\"Exact supplied message\",\"decision\":\"accept\",\"prompt\":\"\"}"
}
```

`switchPage` needs no instruction, route or locator fields. A popup's random runtime handle is available through `PlaywrightEngine.pageHandles()` and operation events; `main` is the stable original-page handle. Do not persist a previous run's random handle as a future parameter. A supplied recipe can return to `main` without using AI. Page switches invalidate observations/frame IDs/CDP sessions. Default unplanned dialogs are dismissed; they do not certify an expected confirmation.

Enable file operations only with an explicit `ui.fileInputRoot` directory (relative to the configuration file). Output belongs to `attempts/<attempt>/private-downloads/`. Raw downloaded files are confidential, checksum-manifested and excluded from public Allure attachments. Upload inputs must remain immutable during an operation. An empty expected download `text` means checksum-only verification; binary files still require the supplied checksum. Applications with arbitrary CPQ widgets, drag/drop, virtual multi-selects or inaccessible controls need their own explicit adapter/coverage assessment.

## Accessibility, visual and performance gates

Consumer JUnit methods `sf.accessibility(rules, impacts, waivers)`, `sf.visual(baseline, environment, channelTolerance, maximumChangedRatio)` and `sf.performance(budgets)` are explicit independent quality checks. A failed quality-module event remains fatal during finalization even if consumer code catches the immediate exception. Their events/metrics accompany Allure quality attachments.

Accessibility bundles **axe-core 4.14.0** from its integrity-verified npm distribution, with its MPL license retained. Supply explicit rule IDs and failing impact levels (`minor`, `moderate`, `serious`, `critical`). Approved frames are audited separately; closed Shadow DOM cannot be certified by axe and fails explicitly. Output contains rule/impact and exact target hashes, not raw DOM/HTML. Incomplete checks require review and fail. Waivers require an exact rule/target hash, meaningful reason and unexpired date. Automated accessibility checks have [documented coverage limits](https://playwright.dev/java/docs/accessibility-testing); they do not replace manual accessibility evaluation.

Visual baselines are created only by explicit `QualityModules.approveBaseline(...)` and cannot overwrite an existing baseline. The sidecar pins browser, viewport, locale, font fingerprint, data fingerprint and PNG checksum. Consumer setup must establish that declared environment; deterministic fonts/data/animations/layout remain its responsibility. Comparison rejects environment/integrity/dimension drift before applying finite per-channel tolerance and changed-pixel ratio. Use masked PNGs and reviewed thresholds. Expected images are never automatically healed.

Performance reads actual browser navigation/paint timing metadata. Supported keys are `domContentLoadedMs`, `responseMs`, `loadMs`, `elapsedMs`, `firstContentfulPaintMs`. Missing/uncompleted measurements are negative and fail an asserted budget rather than passing as zero. Thresholds must be finite, nonnegative and explicitly supplied. These are page timing budgets, not load-test or transaction-throughput certification.

## Failure diagnostics and private retention

```yaml
evidence:
  privateTraceOnFailure: true
  privateRetentionHours: 24
```

Tracing starts after trusted authentication/quality monitoring, not on login routes. Successful runs discard the trace; failing attempts retain a ZIP under their `private-trace/` directory. Playwright snapshots/network content can include credentials and personal/business data. Screenshot masking does **not** sanitize a trace. Private directories require owner-only POSIX permissions or Windows ACLs, ownership/checksum/size manifests and an explicit 1..168 hour expiry; they are excluded from public HTML and automatic attachment export. Default masked PNGs remain the normal report evidence.

`PrivateArtifacts.purgeExpired(directory)` is explicit maintenance: only expired, same-attempt, checksum-matching manifest-listed files can be deleted. It never deletes record journals or whole directories. An expiry marker is not a background purge service: schedule this maintenance and prevent distribution of private files. ZIP retention is bounded to 64 MiB per retained trace. An oversized or unverifiable artifact fails evidence finalization; no sanitized-trace claim is made.

## Apex/Jest/API/mobile results and release matrix

`run-release-matrix --matrix private/matrix.json --report-root report` executes explicitly supplied external tool commands and securely imports their JUnit XML. Apex, Jest, API, web and mobile remain distinct Allure layers. Tool installation, org provisioning, authentication, test authoring and generated output naming belong to each installation. Salesforce recommends separate [LWC Jest checks](https://developer.salesforce.com/docs/platform/lwc/guide/testing.html); those are not browser E2E recipes.

A matrix contains `schemaVersion: 1` and `entries`. Each entry declares `id`, `type`, `provenance`, `workingDirectory`, `command` array, `versionCommand` array, `exactVersionOutput`, `results`, `timeoutSeconds` (1..3600) and `credentialRefs` array. Paths are relative to the matrix/working directory. Provenance requires target, environment, release, runner, runnerVersion, commit and reviewed metadataHash. Environments include `preview-sandbox`, `production-sandbox`, `scratch`, `component`, `device`. Pin the **exact actual version-probe output** in both version fields; do not infer it from a label. Use version-pinned consumer wrappers where Salesforce/Jest result filenames need normalization, and have the version command expose the actual delegated tool version.

Alternatively, a `jest` entry can supply `componentPlan` with exactly matching provenance and omit generic command/directory/results/deadline fields. The concrete bundled LWC/Jest adapter then probes the installed pinned Node/package, runs only selected files and retains owner-only original diagnostics under that layer's owned directory. Standalone `run-components` uses the same adapter. See [component plans and native matrix integration](EFFICIENCY_WORKFLOWS.md#3-concrete-lwcjest-component-runner).

The full plan is preflighted. Commands run as argument arrays, with bounded deadlines and no shell interpolation. Common secret environment variables are removed; only each entry's declared credential references are restored. Tool-managed credential stores and access controls must also be isolated by the installation. Raw runner stdout/stderr are discarded rather than published. Version probes are bounded to 4096 bytes/30 seconds. Existing result files are rejected before dispatch, missing/empty/skipped/unsafe XML cannot pass, and a nonzero runner exit fails even if XML claims success. Import disables DTD/entities/external resources and retains input checksum/provenance. Failed/unstarted layers are explicit infrastructure results, so the aggregate still receives Allure HTML. The process exit and combined gate fail if **any** target/layer fails; one layer cannot overwrite another.

`report verify --run <directory>` and `report build --run <directory>` recognize release reports as well as existing UI suites/workflows. Preview/current targets compare the same consumer-authored expected outcomes; schema fingerprints/metadata hashes are reviewed inputs, not automatic release approval.

## Additional web engines and native mobile

```yaml
ui:
  browser: firefox
  shadowPolicy: tracked
```

`chromium`, `firefox` and `webkit` use the same semantic/action/evidence contract with distinct engine versions and locator namespaces. Default Chromium `shadowPolicy: cdp` additionally detects untracked closed roots through CDP. Non-Chromium requires explicit `tracked` policy: the init script traverses open/tracked closed roots, but cannot prove absence of pre-existing untracked roots without CDP. This declared coverage boundary must match the target's acceptance criteria. Install the requested binaries with the bundled Playwright CLI. Local real-browser checks cover fresh resolution, tracked closed actions, stale rejection and masked evidence in all three engines; private org compatibility still needs representative flows.

`sf-e2e-mobile` supplies `MobileSession`, a separate W3C/Appium native backend. Use a provisioned Android/iOS device and explicitly supplied `platformName`, `appium:automationName`, application/device/version capabilities. Session creation confirms platform/driver. Maps include requested/confirmed capability fingerprints and checksum; web maps/recipes cannot be reused. Explicit accessibility-ID/resource-ID targets require an exact accessible identity. Every operation freshly resolves exactly one visible/enabled element. Wrong identities, duplicates and permission failures stop before dispatch; uncertain actions are never repeated.

Save reviewed targets/alternatives with `saveMap`, then use path-based `click(file,key)`/`fill(file,key,value)` for automatic pre-dispatch fallback promotion, optimistic file locking, archived history and persistent restart reuse. In-memory-map overloads resolve reviewed alternatives without modifying a file. Native assertions compare supplied outcomes independently. The adapter contains no model and does not silently infer native targets from a browser map. AI-assisted native hierarchy discovery is not part of the browser learning workflow.

Native screenshot pixels have a different masking contract: `privateScreenshot` persists bounded, manifested **confidential raw** PNGs, never browser-safe/public attachments. Use an external native JUnit runner and the mobile release layer for its separate evidence/verdict. Android/iOS protocol fault checks use a local W3C service; a real Salesforce Mobile/device session has not been certified in this workspace. Appium's [capability documentation](https://appium.io/docs/en/latest/guides/caps/) defines the installation/driver contract.

## Timing and failure classification

Action attempts, recipe steps, masked capture, REST/native commands and Allure rendering produce metadata-only durations. Attempt timing totals are persisted beside events and included in Allure quality JSON; render timing belongs to the timestamped run. Categories distinguish environment, action, assertion, evidence and cancellation. Optional timing publication cannot turn a passed action into a failure or replace its original error. Timing evidence is observational; it never changes expectations or bypasses a gate. Nested phase totals must not be summed as if they were independent wall-clock intervals.

See [verification](VERIFICATION.md) for executable fault checks and the evidence boundary for each layer.
