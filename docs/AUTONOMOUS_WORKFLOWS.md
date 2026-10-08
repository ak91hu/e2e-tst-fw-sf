# Autonomous workflows and agent integration

[Documentation index](README.md) · [Project README](../README.md) · [Agent instructions](../AGENTS.md) · [Case contracts](CASE_WORKFLOWS.md)

## Contents

- [Choose a capability](#choose-a-capability)
- [Create a consumer charter](#create-a-consumer-charter)
- [Plan and execute discovered workflows](#plan-and-execute-discovered-workflows)
- [Inspect and reproduce findings](#inspect-and-reproduce-findings)
- [Generate an application verification skill](#generate-an-application-verification-skill)
- [Use the local MCP server](#use-the-local-mcp-server)
- [Protect and reuse authorized sessions](#protect-and-reuse-authorized-sessions)
- [Select failures and partition CI work](#select-failures-and-partition-ci-work)
- [Implementation and regression map](#implementation-and-regression-map)
- [Limits and adoption boundaries](#limits-and-adoption-boundaries)

## Choose a capability

| Available input / need | Entry point | Result |
| --- | --- | --- |
| Known visible routes only | `discover-ui` | Read-only inventory and verified presence checks; no business oracle invention |
| Consumer goal, authorized action instructions and independent expected results | `explore-cases` | UI-informed workflow specifications and private reproduction inputs |
| Explicit permission to execute those discovered workflows | `explore-cases --execute` | Each workflow learns in a fresh authenticated context; successful recipes become a private deterministic suite |
| Existing reviewed dataset suite | `verification-skill` | Private application `SKILL.md` with offline fingerprint checks and deterministic verification commands |
| An MCP-capable external agent | `mcp --workspace` | Local tools and bundled guidance; execution, model and secret capabilities require host startup flags |
| Authorized reusable browser state | `session encrypt` / MCP `session_export` | Expiring AES-256-GCM envelope accepted directly by storage-state authentication |
| Failed datasets / distributed workers | `--last-failed` / `--shard` | Explicit current-input-bound failure selection and dependency-preserving partitions |

All commands use the existing Java 21+ CLI JAR. Learning/discovery require an installed configured browser, an authorized JWT or external session, a compatible model endpoint and mapping configuration with writable locators. Replay requires no model. These capabilities do not ship customer workflows or create business seed data.

## Create a consumer charter

A charter is a private schema-1 JSON document. It provides authorization and expectations separately from UI evidence. This generic form example illustrates the format, not an org-specific case:

```json
{
  "schemaVersion": 1,
  "id": "TST_reviewed_flow",
  "goal": "Find useful workflows that verify the supplied result within the approved form",
  "routes": ["/app"],
  "allowedActions": ["fill", "click"],
  "allowedInstructions": ["Input", "Submit"],
  "oracles": [
    {"kind": "expectText", "instruction": "Result", "scope": null,
     "route": null, "valueRef": "expected", "recordNameRef": null}
  ],
  "maxCases": 3,
  "maxSteps": 10
}
```

Supply values separately through `--params <private-values.local.json>`; the example expects `expected` and may use a consumer-provided `input` parameter. The caller must establish what the expected result should be and make the application/data ready for the authorized actions.

| Charter field | Enforcement |
| --- | --- |
| `schemaVersion`, `id`, `goal` | Version 1; generated-case naming; nonblank goal up to 8,000 characters |
| `routes` | 1..20 distinct application-relative routes; both initial and generated `open` routes must belong to this set |
| `allowedActions` | Subset of click, fill, select, setChecked, press, lookup, multiSelect, richText, workspaceTab, gridFind |
| `allowedInstructions` | Up to 100 exact consumer-owned semantic control names/labels; required when actions are allowed. Runtime learning must resolve the instruction to that exact name/label, so a model cannot substitute another destructive control |
| `oracles` | 1..50 valid `CaseSpec` assertions; every generated assertion must equal a supplied oracle, including scope, route and value references. Targeted oracle instructions also name an exact semantic control/label |
| `maxCases`, `maxSteps` | 1..20 distinct plans; 1..100 steps per plan; each case must end with a caller-owned assertion |
| Runtime references | `valueRef` and `recordNameRef` must name supplied parameters; model-invented literals/setup values are rejected |

The allowlists grant a scope; they are not a sandbox for a malicious authenticated application. Review UI-changing names/labels, routes and parameters against the intended environment. Use `learn-case` for ordinary free-form target descriptions; chartered exploration requires exact names/labels for runtime authorization. File operations, arbitrary scripts, coordinates, authentication actions and unapproved instructions are outside this charter grammar. Implicit navigation caused by an explicitly approved UI action remains within the configured exact application origins; `routes` constrains planning/start/explicit `open` destinations, not every application redirect.

## Plan and execute discovered workflows

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar explore-cases --config project.local.yaml --charter charter.local.json --params values.local.json --output-dir artifacts/exploration-01 --locator-mode read-write
```

The planner navigates only the charter routes and requires complete stable current observations. It sends projected semantic controls and parameter names, not field values/free body text, to the model. Known registered secrets are redacted. UI content is untrusted evidence, not an instruction source. Model responses pass a closed bounded schema, charter checks and duplicate-plan/ID rejection before specs are retained.

Without `--execute`, generated business specs stay unverified. A separately verified presence recipe records the first charter route's surface; its successful outcome does not verify the business plans. Inspect the specs and all retained scope/values before executing.

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar explore-cases --config project.local.yaml --charter charter.local.json --params values.local.json --output-dir artifacts/exploration-02 --locator-mode read-write --execute
```

`--execute` explicitly authorizes the charter-bounded mutations. Each planned case uses a new authenticated context and the normal learner: fresh unique target resolution, independent deterministic assertions, configured model/action/time budgets, masked evidence, browser quality gates and final report publication. It stops after the first unsuccessful case and records subsequent plans as skipped. An uncertain dispatched mutation is never automatically repeated. Each planning/learning attempt has its configured budget; total campaign cost can include one planning attempt plus up to `maxCases` learning attempts. Set both limits deliberately.

The output directory must be new. It receives enforced owner-only permissions, a confidential policy and file manifests. Failed planning/surface verification retains a failure receipt and existing inputs. Successful learning produces only verified recipes; failed assertions never produce a passing recipe. The printed report paths identify the independent Allure runs.

## Inspect and reproduce findings

| Private bundle artifact | Meaning |
| --- | --- |
| `charter.local.json`, `parameters.local.json` | Original consumer boundaries and reproduction values; never publish these as public evidence |
| `<case-id>.spec.local.json` | Editable retained intent; presence of this file does not mean it passed |
| `exploration.json` | Charter fingerprint, planned IDs, `uiVerified: false` and execution intent |
| `<charter-id>_surface.json` | Verified presence recipe for the first route, independent of business verification |
| `<case-id>.recipe.local.json` | Created only after a successful learning/evidence/quality attempt |
| `findings.json` | Planned/passed/failed/skipped outcomes, failure code/classification and parameter-file reference; an early failure has a phase-specific receipt |
| `verified-suite.local.json` | Only successfully learned rows; may cover a subset of plans and never claims complete application coverage |
| `REPRODUCTION.md` | Explicit rerun guidance; generated after successful surface planning |
| `private-policy.json`, `*.manifest.json` | Confidential ownership, integrity and seven-day retention metadata |

Application-outcome classification applies only to explicit assertion/quality codes. Authentication, missing seed data, ambiguous controls, exhausted budgets or framework faults remain unverified findings. Inspect the underlying case report before diagnosing a product bug. A model's opinion is not a deterministic verdict.

To reproduce a failed spec, invoke `learn-case --spec <retained-spec> --charter charter.local.json --params parameters.local.json` with matching configuration and a **new** recipe output. The optional `--charter` requires a reviewed spec and preserves its original routes, exact control names/labels and independent oracles before target actions. This runs the actions again and needs fresh/repeatable consumer data and existing authorization. Do not automatically replay an uncertain mutation or use newly observed actual values as replacement expectations. Verified recipes can use ordinary deterministic `replay-case` or `run-suite`. MCP `learn_case` accepts the same optional workspace-relative `charter` argument.

Manifests make the bundle eligible for explicit `PrivateArtifacts.purgeExpired(directory)` maintenance after expiry. There is no background janitor. That API deletes only unchanged, owned manifested files; policy files/directories remain. Protect and remove copies according to the consumer's retention rules.

## Generate an application verification skill

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar verification-skill --config project.local.yaml --suite suite.local.json --output-dir .agents/skills/application-verification
java -jar sf-e2e-cli/target/sf-e2e.jar verification-skill --config project.local.yaml --suite suite.local.json --output-dir .agents/skills/application-verification --check
```

Generation preflights the complete deterministic suite offline before creating a new owner-only directory. It embeds paths, commands and fingerprints, never parameter values, credentials or org-specific framework tests. `verification.json` binds the suite manifest, effective deterministic configuration, recipes and parameters. The unordered resource sets used in new receipt fingerprints are normalized; historical recipe/checksum serialization is unchanged.

`--check` re-preflights and compares current inputs with that receipt. Changed inputs return `SELECTION_STALE`; review and regenerate into a new directory. The skill tells another agent to check fingerprints, validate the suite, execute with strict locators, verify reports, retain every dataset outcome and keep independent assertions unchanged. CLI installation paths in generated commands are placeholders relative to the consuming project; adjust the installed JAR path. It is consumer-local guidance, not an automatically installed personal/global skill.

## Use the local MCP server

Default capabilities need no target session or YAML for guide/spec tools:

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar mcp --workspace C:/consumer-project --config project.local.yaml
```

Configure the MCP client to launch `java` with separate argument-array entries for `-jar`, the absolute CLI path, `mcp`, `--workspace`, the absolute consumer directory, `--config` and the workspace-relative configuration path. Spaces in paths remain inside one argument. Do not pass credentials as command arguments.

| Host startup flag | Additional tools |
| --- | --- |
| None | `guide`, `validate_case`, `validate_suite`, `sessions_list`; all bundled guides are also read-only MCP resources |
| `--allow-model` | `plan_case` |
| `--allow-execution` | `replay_case`, `run_suite`, `session_open`, `session_observe`, `session_action`, `session_close` |
| Both flags | `learn_case`, `explore_cases`; exploration executes only with tool argument `execute: true` |
| `--allow-execution --secret-ref APPROVED_REF` | `session_fill_secret`; only exact startup-approved environment references |
| `--allow-execution --export-key-ref APPROVED_KEY_REF` | `session_export`; only exact startup-approved encryption-key references |

There is no browser/script/shell evaluation tool. File arguments are workspace-relative, reject traversal/absolute paths and reject existing ancestor links resolving outside the workspace. The configuration path is fixed at host startup; closed schemas reject tool arguments attempting to override configuration or capability flags. The host-approved configuration still determines target origins, credentials, budgets and artifact directories; keep that configuration under host control.

Managed sessions have opaque IDs, fresh isolated contexts and configured deadlines. At most four can coexist; the existing org/identity locks prevent overlapping local runs with the same authorized identity. Sessions stay on the server's owner thread and close on explicit close, the next request after expiry, or process cleanup. Tool calls are serialized. Separate host-managed processes/personas can orchestrate independent campaigns; this server does not silently spawn parallel agents.

`session_action` supports one explicit click/fill/select/setChecked/press on a fresh unique semantic role/name/scope. Values are required exactly for actions that need them. Post-dispatch errors remain uncertain and receive no automatic mutation retry. Use opaque host fill for secrets, never model-visible literal values. These interactive actions are not verified recipes or proof of a business assertion.

Transport follows the [official MCP stdio contract](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports): UTF-8 newline-delimited JSON-RPC, protocol-only stdout, diagnostics on stderr. Initialize with `protocolVersion`, `clientInfo` and `capabilities`, then send `notifications/initialized`. Supported negotiated versions are `2025-11-25` and `2025-06-18`. Tool schemas/annotations follow the [official tools contract](https://modelcontextprotocol.io/specification/2025-11-25/server/tools). Requests and tool payloads are bounded to 1 MiB; oversized/invalid wire messages return errors without corrupting subsequent messages. Tool failures use `isError: true`; unknown methods/invalid calls use JSON-RPC errors.

This is a synchronous local stdio subset. HTTP hosting, roots negotiation, subscriptions, prompts, sampling, elicitation, task/progress APIs and interruption of an already running tool by cancellation notification are not supplied. In-flight operations rely on their configured deadlines; terminate the local process if the host must stop immediately, retain uncertain mutation results, then inspect journals before rerunning. No HTTP authentication/public-service security is claimed.

## Protect and reuse authorized sessions

The consumer provides a **random 32-byte key**, base64 encoded, through an environment reference. Generate it using the host's cryptographic tooling; keep it separate from the exported state and logs. Do not copy the synthetic all-zero key used by internal contract fixtures into deployment.

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar session encrypt --file authorized-state.local.json --output encrypted-state.local.json --key-ref SESSION_ENCRYPTION_KEY --origin https://your-authorized-host.example --ttl-hours 8
```

The input may be authorized plaintext or an existing valid encrypted state. The command validates it against exact consumer origins and writes a new encrypted envelope. AES-256-GCM uses a random 96-bit nonce and authenticated schema/algorithm/key reference/origin fingerprint/creation/expiry metadata. Lifetimes are 1..168 hours; tampered metadata/ciphertext, wrong keys/origins and expired/not-yet-valid sessions fail before browser creation. Plaintext remains bounded to 4 MiB; envelopes to 6 MiB. HTTPS or explicit loopback origins are required.

Point the existing persona `storageStateRef` environment variable at the encrypted file. `BrowserState.load` resolves the envelope key reference on the host, authenticates/decrypts in memory, validates cookie/storage boundaries and registers session secrets for redaction. It does not write decrypted plaintext. Legacy authorized plaintext state remains supported and must still be protected externally. Expiry does not revoke server-side sessions; the consumer retains responsibility for authorization, key rotation, revocation and file deletion.

MCP `session_export` captures the current authorized cookies/storage, including supported IndexedDB state, only into a new encrypted output. `session_fill_secret` resolves the approved reference on the host, never returns its value, registers redaction and permanently prohibits PNG capture and raw tracing in that context. An already active trace must be stopped before opaque filling. Withholding begins before dispatch and persists even if the fill fails; a fresh session is required for later evidence capture. Do not convert withheld evidence into a passing screenshot requirement.

## Select failures and partition CI work

```powershell
java -jar sf-e2e-cli/target/sf-e2e.jar validate-suite --config project.local.yaml --suite suite.local.json --shard 1/3
java -jar sf-e2e-cli/target/sf-e2e.jar run-suite --config project.local.yaml --suite suite.local.json --shard 1/3
java -jar sf-e2e-cli/target/sf-e2e.jar run-suite --config project.local.yaml --suite suite.local.json --last-failed report/previous-run
```

Shards use one-based `index/count` with `1 <= index <= count <= 500`. Transitive dependency components remain together. Sorted component roots are distributed round-robin, with original selected row order preserved. Shard ownership is stable for unchanged manifests/selections, not duration-balanced. Empty shards fail with `NO_TESTS`; choose a count no larger than the number of dependency components. Every CI shard must use the same complete manifest and selection inputs, and keep its own report/journal directories and appropriate identity/resource isolation. Local file locks do not coordinate separate CI machines; the consumer must allocate identities/data across hosts.

`--last-failed` first verifies the saved aggregate and child evidence/Allure integrity. It binds the suite manifest and each selected row's recipe, parameter and namespace fingerprint to current inputs, selects all recorded non-passed rows, and includes passed prerequisite closure. It excludes manual dataset/tag and impact filters; optional sharding applies after closure. Changed inputs or legacy reports without these new fingerprints fail as stale. The all-passed receipt fails with `NO_TESTS` rather than creating an empty green rerun. Previous receipts/recipes are preserved. This is an explicit new run, not a retry policy or flaky-pass classification.

New suite reports retain additive `suiteSourceHash`, dataset `inputHash`, optional `shard` and `selectionSource` provenance. Existing report verification and saved recipes remain compatible. Shards produce separate complete reports for their own selections; the framework does not merge them into one Allure run. Consumers must aggregate all shard exit codes and keep the original reports, rather than inferring complete-suite success from one passing shard. Missing shards remain missing work.

## Implementation and regression map

| Contract | Source | Meaningful checks |
| --- | --- | --- |
| Charter authorization/oracle preservation | `core/scenario/ExplorationCharter`, `agent/CaseExplorer` | `ExplorationCharterTest`, `CaseExplorerTest`; unauthorized instruction/oracle/route/ref, duplicate plans, missing complete surface, projected-value privacy |
| Real workflow planning/learning/replay | `cli/ExplorationCommands`, existing learner/runner | `CaseWorkflowHarnessIT`, `ExplorationCommandsHarnessIT`; local HTTP model/UI, actual mutations, provider-stopped replay, production CLI orchestration with injected local sessions, unauthorized target rejection and private failed repro retention |
| MCP handshake/policy/wire limits | `cli/McpCommands` | `McpCommandsTest`; disabled capabilities, closed inputs, path/ID faults, resources and byte-bounded multibyte-message recovery |
| Encrypted state/opaque fill | `core/SessionVault`, `core/BrowserState`, `engine/PlaywrightEngine`, `cli/SessionCommands` | `SessionVaultTest`, `SessionSecurityHarnessIT`; nonce randomness, authentication/origin/expiry faults, host-only values, PNG/trace prohibition, real cookie/storage restoration |
| Failure/shard selection and provenance | `core/scenario/SuiteSelection`, `cli/SuiteCommands` | `SuiteSelectionTest`, `SuiteCommandsTest`; dependency closure, stale receipts, partitions/empty rejection and report verification |
| Consumer verification skill | `cli/VerificationSkillCommand` | `SuiteCommandsTest`; production offline generation, fingerprint check, changed-input rejection and existing-output preservation |

Source paths are relative to the matching module's `src/main/java/hu/ak91/sf/e2e/`; tests belong to that implementation module. See [development commands](DEVELOPMENT.md#verification-commands) and the latest dated [verification receipt](VERIFICATION.md). Internal fixtures are synthetic/local; no private org or hosted CI certification follows from them.

## Limits and adoption boundaries

The AI proposes workflows from charter routes; the learner resolves newly revealed controls during actual execution. Neither a model plan, a presence recipe nor a successful subset of learned cases proves all application paths/personas/permissions are covered. Caller-owned expectations and repeatable data are required for business verification. Multi-agent orchestration is a host responsibility using isolated identities, not unrestricted built-in exploration.

Exact origins/frames, semantic uniqueness, immutable expected outcomes, strict-cache integrity and uncertain-dispatch rules remain mandatory. Pixel/coordinate actions, vision-only verdicts, automatic business retries, unrestricted model evaluation, hosted Kernel/EAS/React Native boot, PR posting, telemetry and TypeScript API compatibility remain outside the Java framework contract. The [reference audit](REFERENCE_PRACTICES.md#intentional-differences-and-product-gaps) records these explicit differences and the implemented follow-up separately.
