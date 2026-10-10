# Automatic Allure reporting

[Documentation index](README.md) · [Project README](../README.md) · [Agent instructions](../AGENTS.md) · [Development guide](DEVELOPMENT.md)

The report engine is official Allure 3.20.1, bundled during the Maven build. JUnit `run`, dataset `run-suite`, file learning/discovery/replay, `run-components` and `run-release-matrix` automatically render after execution, including unsuccessful attempts. Reporting uses no AI. Completed supported runs also generate context-separated `trends.html`; see [efficiency workflows](EFFICIENCY_WORKFLOWS.md).

The command prints the run's `index.html`. Open it directly in a browser: Allure's single-file output embeds the report application, data and attachments. The report is also available at `allure-report/index.html`. No global Allure installation, report server or runtime internet download is required. Node.js 26.10.0 or later runs the bundled engine; install Node on `PATH` or set the JVM property `-Dsf.e2e.nodeExecutable=<absolute node executable>`. Maven invokes the build-time npm adapter with the checked-in lockfile and bundles its pinned installation; first runtime use extracts it into a hash/version-specific cache under the user's `.sf-e2e/tools` directory.

Published standalone HTML includes a content security policy that permits embedded resources and blocks external network requests, including upstream analytics. This policy is applied before the published HTML checksum is recorded.

## Contents

- [What the report contains](#what-the-report-contains)
- [Screenshots are enabled by default](#screenshots-are-enabled-by-default)
- [Files and commands](#files-and-commands)

## What the report contains

The [advanced capability guide](ADVANCED_CAPABILITIES.md) describes additional independent accessibility/visual/performance events, API usage, phase timing and release-layer provenance. Attempt timing totals accompany quality JSON; run-owned `render-timing.json` measures the renderer without changing its verdict. Backend expectation and downloaded-content mismatches appear as failed assertions rather than broken infrastructure.

Private traces/downloads/native screenshots have owner-only permissions, explicit ownership/checksum/size/expiry manifests and no public Allure attachment. Trace snapshots/network content are confidential raw evidence: PNG masking does not sanitize them. Failure-only tracing starts after trusted authentication; success discards its trace. Scheduled `PrivateArtifacts.purgeExpired` performs explicit manifest-limited cleanup. Masked browser PNGs remain enabled by default. Release layers retain separate test/provenance verdicts; their external evidence does not substitute for browser completion PNGs.

- Case/persona, execution mode, start/end times and actual verdict.
- Ordered case steps, with incomplete future steps marked skipped.
- An implemented HTML test design for learning attempts with established intent, including planned order, parameter references, completed locator references and unfinished steps; see [the design contract](CASE_WORKFLOWS.md#instruction-order-and-implemented-html-design).
- Masked PNG attachments and a redacted event-log attachment.
- Model-call count/events, locator retries and successful self-healing events.
- AI workflow budgets, fresh-target/response reuse, escalations, charged tokens and reviewed-rate estimated cost; unpriced AI remains unknown in the quality summary. See [AI workflow](AI_WORKFLOW.md).
- Failure diagnostics; authentication/infrastructure failures appear as broken, while assertion failures appear as failed.
- Browser/console/server/transport defects, waived issues and quality-gate outcomes; enabled unwaived application defects appear as failed.

The file workflow's raw execution status remains available in `case-report.json`. JUnit runs retain `run-report.json`, completion manifests, JUnit XML and supplementary `summary.md`/`summary.html` exports. The root `index.html` is the real generated Allure report.

## Screenshots are enabled by default

The framework captures authenticated start and completion states, each completed learned/replayed step or discovered page, mapped actions in Java cases, and failures when the current UI can safely be captured. Step captures are attached to the corresponding Allure step. Optional agent actions/assertions also produce evidence. Planned steps that were never executed do not acquire invented screenshots or passing verdicts.

```yaml
evidence:
  minPngPerPassedResult: 2
  screenshotsAfterSteps: true
  piiLabels: []
```

Set `screenshotsAfterSteps: false` to omit additional step/action images; required start/completion and safe failure evidence remain active. Add the target app's sensitive labels to `piiLabels`. Existing credential and secret masking applies to every capture. Login screens and unsafe/unobservable UI are excluded. When a failure occurs before a usable page exists, diagnostics are retained without inventing a screenshot.

Browser PNGs mask the actual returned pixels using outward-rounded sensitive rectangles in viewport CSS coordinates, including approved frames and tracked closed roots. The engine checks rectangles and root/frame coverage before and after capture; changes or missing geometry fail as `EVIDENCE_WITHHELD`. The unmasked buffer stays in memory and is never published. Inert/accessibility-hidden content can still be painted and is therefore included in mask detection. No browser overlay or application DOM modification is required. Known transformed/truncated secret matching and its limits are documented in [the evidence contract](IMPLEMENTATION.md#evidence-and-boundaries).

PNG ownership and SHA-256 checks are retained. A completion screenshot records the final UI state; the passing verdict still requires subsequent quality/evidence gates. Browser errors delivered during completion capture can fail the case, and defects delivered during original-failure capture remain secondary diagnostics. Screenshot failure after an interaction does not justify repeating a dispatched business action. Keep reports private when application labels or visible business data require it.

## Files and commands

The default run root is `report/<UTC timestamp>`, for example `report/2026-10-06_08-38-32.122005600Z`. `report.root` defaults to `report` and can point to another directory. Relative roots resolve against the selected YAML file's directory. All reporting commands use the same atomic directory reservation, including mapping/inspection/validation diagnostics. Artifacts survive Maven clean and `report/` is ignored by Git.

Names encode the actual start instant as `yyyy-MM-dd_HH-mm-ss.SSSSSSSSSZ` in UTC. Nine fractional digits preserve the clock's available precision; they do not claim nanosecond clock accuracy. UTC avoids daylight-saving ambiguity and permits chronological sorting. A simultaneous reservation for the exact same instant receives `_0002`, `_0003`, etc.; earlier evidence is never overwritten. JUnit and file-workflow run IDs match their reserved folder names. Rebuilding a report stays inside the chosen original directory. Historical artifacts under older/custom roots remain there.

| Path within the run | Content |
| --- | --- |
| `index.html`, `allure-report/index.html` | Identical standalone Allure HTML reports |
| `allure-results/` | Allure result JSON, PNG copies and redacted event attachments |
| `allure-generation.json` | Engine/version, generation timestamp and HTML checksum |
| `allure-generation.log` | Output of the actual Allure renderer |
| `case-report.json` or `run-report.json` | Structured execution data used for rebuilding |
| `test-design.html` | Learning/maintenance interpreted design and implementation outcomes, also attached to Allure; maintenance includes baseline/new step origins, checksums, potential shared dependencies and actual repairs; absent before a plan is established |
| `suite-report.json`, `cases/<dataset-id>/` | Aggregate dataset verdicts/checksums and isolated original row evidence |
| `quality-summary.json`, per-attempt `browser-diagnostics.json` | Assertion, drift/retry, model-call event and browser-quality metrics |
| `attempts/` | Original evidence, artifact manifests and redacted event journals |
| `impact-plan.json` | Reviewed change-impact selection reasons and input checksums, when impact selection is used |
| `run-metrics.json`, `trend-summary.json`, `trends.html` | Checksummed normalized metrics, comparable retained history and an interactive p50/p95 dashboard |
| `private-components/`, matrix `layers/<target-hash>/<id>/private-components/` | Owner-only original component invocation/results/diagnostics with expiry manifests; not public attachments |

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar report verify --run <run-directory>
java -jar sf-e2e-cli/target/sf-e2e.jar report build --run <run-directory>
java -jar sf-e2e-cli/target/sf-e2e.jar report trends --run <run-directory> --window 100
```

Verification checks evidence and the generated HTML checksum. When a `testDesign` receipt is present, it must be a valid object with the current attempt's attachment filename and SHA-256; both the standalone design and its Allure copy must match and remain inside the run directory. Malformed metadata or a foreign attachment fails verification even when the bytes match. Historical reports without this additive receipt remain readable. Rebuilding consumes saved data and invokes Allure again without opening Salesforce or calling a model. Generation has a 120-second limit. If rendering fails, raw results remain available; the CLI returns a reporting/evidence failure (exit code 4). Inspect `allure-generation.log`, resolve the Java/cache/filesystem issue and rebuild.

After a successful render, both published HTML copies are verified before removing that render's owned `.allure-render-<UUID>` temporary directory. Cleanup checks the resolved parent and never follows symbolic links. Raw results, screenshots, existing report directories and failed-render diagnostics remain preserved. An I/O cleanup failure retains the temporary directory without invalidating the published report; historical temporary outputs are not swept automatically.

Suites render once after selected dataset completion. Allure result names include dataset IDs and history separates datasets/personas. Child cases do not create independent HTML reports; their raw data/evidence remains under `cases/`. Verification checks child completion, evidence ownership/content, unique dataset/attempt identities, case-data checksums and aggregate status. Run/case identities must agree with the parent suite; replay mode and zero model calls must agree with deterministic execution. Rebuild reexports verified child data into known owned result files; unexpected aggregate result files are rejected. A selected failed/skipped/blocked row can never validate as a passing suite. A passing child needs distinct owned captures/completion evidence and cannot contain failed enabled application gates.

`map-ui`, `verify-ui` and `inspect-ui` retain their mapping/locator diagnostic exports. The automatic test-report lifecycle applies to JUnit cases and learn/discover/replay workflows.
