# Change selection, run trends and LWC component checks

These three workflows reduce unnecessary browser work and make execution cost visible while retaining independent assertions, Page Object Model, `TST_` generated names, timestamped Allure and default masked browser screenshots. They require no model calls. The consumer workspace remains empty: coverage manifests, source projects and business checks belong to the target project.

## 1. Reviewed change-impact selection

Map repository paths to **dataset IDs from a saved recipe suite**. A dataset is an invocation of a verified recipe, persona and optional private parameter file. The mapping is reviewed coverage metadata; the framework does not infer business impact from a filename or claim complete coverage merely because a path matches.

For a suite containing datasets `setup`, `workspace` and `navigation`, a coverage document could contain:

```json
{
  "schemaVersion": 1,
  "revision": "coverage-v1",
  "globalPaths": ["shared/**", "package-lock.json", "project.local.yaml"],
  "always": ["navigation"],
  "rules": [
    {"component": "workspace", "paths": ["force-app/main/default/lwc/workspace/**"], "datasets": ["workspace"]},
    {"component": "setup", "paths": ["force-app/main/default/classes/Setup*"], "datasets": ["setup"]}
  ]
}
```

Replace these illustrative IDs and paths with the consumer suite's reviewed coverage. Include shared services, Apex, permissions, layouts, metadata and configuration changes where relevant. One component can select several datasets; several components can select the same dataset. Coverage references outside the suite are rejected.

The changed-path document uses repository-relative forward-slash paths, never absolute paths or globs:

```json
{"schemaVersion": 1, "files": ["force-app/main/default/lwc/workspace/workspace.js"]}
```

The Git collector resolves both supplied revisions to commits and uses a NUL-delimited diff. Renames include both the removed and new path, and deletions remain included. It does not assume a merge base, include uncommitted edits or fetch remote refs. Supply the reviewed comparison appropriate to your branch and check the workspace/revisions in CI.

```powershell
node scripts/collect-changes.mjs --repo C:\your-project --base <reviewed-base-commit> --head HEAD --output private/changes.json
java -jar sf-e2e-cli/target/sf-e2e.jar plan-impact --suite private/suite.json --coverage private/coverage.json --changes private/changes.json --output private/impact.json
java -jar sf-e2e-cli/target/sf-e2e.jar validate-suite --config project.local.yaml --suite private/suite.json --coverage private/coverage.json --changes private/changes.json
java -jar sf-e2e-cli/target/sf-e2e.jar run-suite --config project.local.yaml --suite private/suite.json --coverage private/coverage.json --changes private/changes.json
```

`plan-impact` is an offline review operation: it validates the suite graph and coverage, without loading recipe files, credentials, browsers or models. It refuses an existing output file. Execution recomputes the plan from the inputs; a previously saved plan cannot bypass validation. `validate-suite` and `run-suite` preflight every selected recipe, parameter file, persona and locator map before authentication. Unselected recipe files are not loaded.

| Situation | Selection and verdict policy |
| --- | --- |
| Reviewed matching component paths | Select mapped datasets, always-run datasets and datasets absent from the coverage inventory |
| Selected dataset depends on another via `after` | Include transitive prerequisites and order them before dependents; existing resource/identity locks still apply |
| A changed path has no mapping, or matches `globalPaths` | Select the entire suite; unknown files are visible in the plan |
| No changed paths | Offline plan says `NO_CHANGES`; attempted execution returns `NO_TESTS` and creates no successful report |
| Coverage/changes supplied individually | Reject the input |
| Impact selection combined with `--dataset` or `--include-tag` | Reject it; filters cannot narrow the full-suite fallback |
| Selected recipe or mapping is invalid | Fail preflight before dispatch |

Patterns support `*` within a path segment, `?` for one character and `**` across directories. `**/` also matches zero directories. Paths are case-sensitive to stay portable across repository platforms. The input limit is 1 MiB each, 10,000 changed files and 2,000 coverage patterns. The suite limit remains 500 datasets. Patterns are compiled once per evaluation.

Every actual selected suite retains `impact-plan.json` beside Allure, including checksums of the suite, coverage and changes, selected/direct/unknown/uncovered datasets and selection reasons. Checksum identity detects input differences; it is not a signature or proof that the coverage mapping is correct. Review the rules whenever application dependencies or business risk changes. Schedule a full regression separately to validate the maintained coverage assumptions.

## 2. Automatic, context-separated trends

After Allure rendering, supported completed JUnit, recipe, suite and release/component runs automatically receive:

| File | Purpose |
| --- | --- |
| `run-metrics.json` | Normalized metric record, input-source SHA-256 hashes and record checksum |
| `trend-summary.json` | Comparable retained history and all-outcome/passed-run p50/p95 statistics |
| `trends.html` | Standalone interactive metric chart and verdict table, linked to that run's Allure |
| `trend-error.json` | An optional observational error code when metrics cannot be generated |

Open `report/<UTC timestamp>/trends.html`. Choose a metric to inspect duration, phase timing, locator retries, healing events, provider attempts, charged tokens, estimated AI cost and Allure render time. Unknown token usage or missing reviewed rates remain `null` and appear as gaps; they never become zero-cost samples. A run with no model calls can correctly report zero AI cost. Framework provider counts do not measure all activity or billing outside the framework.

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar report trends --run report/<UTC-timestamp> --window 100
```

Rebuilding trends is offline and verifies the current Allure evidence. The default window is the newest 50 comparable completed runs; the explicit range is 1..500. It scans sibling timestamp folders with metrics, up to 1,000 indexed runs. Archive older runs or use a narrower report root when that bound is reached. Each source is bounded to 16 MiB and all metric sources together to 64 MiB per run. There is no global mutable history database or runtime dependency on a chart service.

Comparisons require a recorded context hash and matching workflow/selection context. UI workflows separate locator namespaces, org/persona/localization/browser context, execution settings, parameter hashes and reviewed execution scope; suites also identify selected rows, recipe and parameter hashes and worker count. Component and native release-matrix commands hash the explicit plan, including pinned runner, selected files and provenance. Changing a plan intentionally starts a separate cohort. Legacy runs without sufficient context are displayed individually rather than mixed into a target-specific baseline. Commit/metadata changes in a component plan therefore start a new cohort; hold a reviewed performance comparison context constant only when that comparison is appropriate.

Historical records are checked against their record/source hashes; changed, corrupt, mismatched-context and later-completed runs are excluded and counted. Failed outcomes remain visible. All-outcome and passed-run percentiles are separate and include their sample counts. The quantile method is nearest rank; a p95 with few samples is descriptive, not a throughput guarantee. Timing uses wall-clock intervals where available, and explicitly labels imported legacy release totals as `test-duration-sum`. Overlapping phase durations are not additional wall-clock time.

Trends are observational: a dashboard failure records an unavailable status and cannot turn a failed test into a pass or rewrite original Allure/verdict evidence. Source hashes detect accidental changes but do not authenticate a hostile actor able to rewrite every source and checksum. Store reports with the same access controls as the underlying test evidence.

## 3. Concrete LWC/Jest component runner

Salesforce documents that [LWC Jest checks](https://developer.salesforce.com/docs/platform/lwc/guide/testing.html) run without a browser or org connection. They exercise component behavior in a simulated DOM and complement UI integration checks. This adapter uses the installed official [`@salesforce/sfdx-lwc-jest`](https://github.com/salesforce/sfdx-lwc-jest) package; it does not implement a substitute component engine or test Aura/native rendering.

Prepare the consumer Salesforce source project:

1. Supply its `sfdx-project.json`, `package.json`, committed npm lockfile and meaningful component assertions under `__tests__/*.test.js` or `__tests__/*.spec.js`.
2. Select an exact Node version and an exact Salesforce Jest package version appropriate to the application's release. Review Salesforce's release tags; `latest` alone does not establish production compatibility.
3. Pin `@salesforce/sfdx-lwc-jest` in `devDependencies` without a range and restore dependencies with `npm ci`. The adapter requires matching manifest, lockfile and installed package versions; it neither installs nor upgrades packages during tests.
4. Supply reviewed provenance and an explicit list of files. Broad implicit discovery is excluded so an accidentally empty selection cannot certify a component layer.

The following is an illustrative plan. The versions match a local development receipt; choose the target project's reviewed versions. Replace the commit and metadata hash with actual source provenance, and resolve `projectDirectory` relative to this plan's directory:

```json
{
  "schemaVersion": 1,
  "projectDirectory": "../salesforce-project",
  "nodeExecutable": "node",
  "expectedNodeVersion": "v26.10.0",
  "expectedLwcJestVersion": "7.9.0",
  "tests": ["force-app/main/default/lwc/workspace/__tests__/workspace.test.js"],
  "timeoutSeconds": 60,
  "provenance": {
    "target": "component-source",
    "environment": "component",
    "release": "reviewed-release",
    "runner": "sfdx-lwc-jest",
    "runnerVersion": "node=v26.10.0;sfdx-lwc-jest=7.9.0",
    "commit": "replace-with-source-commit",
    "metadataHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
```

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar run-components --plan private/components.json --report-root report
```

The Java CLI bundles the Node runner and custom [Jest reporter](https://jestjs.io/docs/configuration#reporters-arraymodulename--modulename-options). It probes the actual installed versions, executes only the selected real contained files with `--runTestsByPath --runInBand --ci`, and never requests watch mode or snapshot updates. Node arguments are separate process arguments, without shell interpolation. The deadline bounds execution and terminates a still-running process tree. Project configuration and component code are explicitly supplied executable consumer code, not an untrusted-code sandbox.

Common Salesforce and credential environment variables are removed before Jest dispatch; component execution needs no org login or AI. Each attempt owns new result files, never stale reused XML. Nonzero runner exit, compilation errors, wrong assertions, empty results and all-skipped results cannot pass. Mixed skipped results also keep the component layer unsuccessful under the existing strict external-layer policy. Reporter-generated case names begin with `TST_`.

Allure uses the normalized test verdicts, timings, layer identity and pinned provenance. Browser screenshots remain enabled for browser cases; a Jest simulated DOM cannot supply a real browser completion PNG. Original assertion messages and raw diagnostics stay in owner-only `private-components/`, with ownership/checksum/size/24-hour expiry manifests. They are not attached to public Allure. Existing `PrivateArtifacts.purgeExpired` can remove expired manifested private artifacts; expiry does not by itself schedule a background deletion task.

### One release report with separate component and integration layers

`run-release-matrix` accepts a native `componentPlan` entry. It preflights component-plan structure, project availability and exact matching provenance before starting any layer. Runner probes and selected-file validation occur during the component attempt. Other entries retain their explicit Apex/API/web/mobile subprocess contracts. A component entry cannot also declare generic commands, credentials or a second deadline.

```json
{
  "schemaVersion": 1,
  "entries": [
    {
      "id": "lwc",
      "type": "jest",
      "componentPlan": "components.json",
      "provenance": {
        "target": "component-source",
        "environment": "component",
        "release": "reviewed-release",
        "runner": "sfdx-lwc-jest",
        "runnerVersion": "node=v26.10.0;sfdx-lwc-jest=7.9.0",
        "commit": "replace-with-source-commit",
        "metadataHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      }
    }
  ]
}
```

Add the consumer's explicit integration-layer entries using the [release-layer contracts](ADVANCED_CAPABILITIES.md#apexjestapimobile-results-and-release-matrix). Component-plan paths resolve relative to the matrix; source-project paths resolve relative to the component plan. The matrix retains component-private evidence under `layers/<target-hash>/<layer-id>/`, imports each outcome and renders Allure once. A passing component cannot conceal a failed API/UI layer; any unsuccessful layer fails the aggregate. Native component attempts are independent of browser workers.

## Verification and practical limits

Internal checks cover mapping fallback/dependency closure, no-change rejection, unselected-input isolation, source checksum/context separation, missing AI cost, reporter verdicts, version mismatch, empty/skipped suites and mixed component/API release failure. Node adapter contracts and Git rename/revision checks run in CI alongside existing framework checks. Local development also exercises an actual installed Salesforce LWC/Jest package against a small component, including a deliberately wrong expectation. Those fixtures live outside the consumer workspace.

These workflows reduce redundant work and improve diagnosis; they do not establish a universal Salesforce speedup or complete business coverage. Measure representative application flows with the dashboard, maintain reviewed coverage and meaningful assertions, and retain full regression and UI checks for behavior beyond a simulated component DOM. See [verification](VERIFICATION.md), [the efficiency assessment](EFFICIENCY.md) and [POM/naming](PAGE_OBJECT_MODEL.md).
