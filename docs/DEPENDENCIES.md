# Stable tooling snapshot

The direct stack was checked against Maven Central, npm and official GitHub releases on **October 6, 2026**. Versions are exact and reproducible; prerelease versions are not selected as framework dependencies. Upstream transitive dependencies are resolved using their supported constraints and the committed npm integrity lockfile, rather than arbitrary major-version overrides.

| Component | Stable version | Source |
| --- | --- | --- |
| Build/CI JDK | Temurin 27+35 | [Official release](https://github.com/adoptium/temurin27-binaries/releases/tag/jdk-27%2B35) |
| Java bytecode baseline | 21 | Retained for existing Java consumers; this is a compatibility target, not the installed build JDK |
| Maven distribution | 3.10.0 | [Maven downloads](https://maven.apache.org/download.cgi) |
| Maven Wrapper | 3.3.4 | [Wrapper documentation](https://maven.apache.org/wrapper/) |
| Playwright Java and browsers | 1.63.0 | [Maven Central](https://repo.maven.apache.org/maven2/com/microsoft/playwright/playwright/maven-metadata.xml) |
| JUnit BOM / Jupiter / Platform | 6.1.3 | [Maven Central](https://repo.maven.apache.org/maven2/org/junit/junit-bom/maven-metadata.xml) |
| Jackson BOM / databind / YAML | 3.2.3 | [Maven Central](https://repo.maven.apache.org/maven2/tools/jackson/jackson-bom/maven-metadata.xml) |
| Bouncy Castle | 1.86 | [Maven Central](https://repo.maven.apache.org/maven2/org/bouncycastle/bcpkix-jdk18on/maven-metadata.xml) |
| picocli | 4.7.7 | [Maven Central](https://repo.maven.apache.org/maven2/info/picocli/picocli/maven-metadata.xml) |
| Allure Report | 3.20.1 | [npm registry](https://registry.npmjs.org/allure/latest), [Allure 3 migration](https://allurereport.org/docs/v3/migrate/) |
| Node.js | 26.10.0 | [Official releases](https://nodejs.org/dist/index.json) |
| npm | 12.2.0 | [npm registry](https://registry.npmjs.org/npm/latest) |
| axe-core (bundled browser asset) | 4.14.0 | [npm registry](https://registry.npmjs.org/axe-core/latest) |
| Consumer LWC Jest example | 7.9.0 | [Official package](https://registry.npmjs.org/@salesforce/sfdx-lwc-jest/latest) |
| Compiler plugin | 3.16.0 | Maven Central |
| Surefire / Failsafe plugins | 3.6.0 | Maven Central |
| Clean / Resources plugins | 3.5.0 | Maven Central |
| Jar plugin | 3.5.1 | Maven Central |
| Install plugin | 3.2.0 | Maven Central |
| Shade plugin | 3.6.2 | Maven Central |
| Exec plugin (Allure bundling) | 3.6.4 | Maven Central |
| actions/checkout | 7.0.1 | Official GitHub release; full commit SHA pinned in both workflows |
| actions/setup-java | 6.0.1 | Official GitHub release; full commit SHA pinned in both workflows |
| actions/setup-node | 7.0.0 | Official GitHub release; full commit SHA pinned in both workflows |
| actions/upload-artifact | 7.0.1 | Official GitHub release; full commit SHA pinned in both workflows |

## Allure 3 build and runtime

Install Node.js 26.10.0 or later and npm 12.2.0 before invoking Maven. The reporting module runs `allure-engine/bundle.mjs` during `generate-resources`. It installs the exact Allure dependency tree with `npm ci --ignore-scripts`, verifies the top-level version and embeds the installation as a ZIP resource. Successful bundles are reused only when the manifest, lockfile, script and archive checksums match. Maven `clean` removes the bundle and forces a reproducible rebuild.

Runtime reporting extracts that resource into a version/hash-specific cache, then invokes the installed Node executable directly. There is no global Allure requirement, `npx`, runtime npm installation, model call or host-project plugin discovery. Only the official Awesome report plugin is enabled with `singleFile: true` and English output. Published HTML blocks external network requests, including upstream analytics, through an embedded content security policy. Screenshots, assertion verdicts, raw results, UTC timestamp folders, atomic HTML publication, integrity metadata and trend reporting remain framework responsibilities.

The JVM option `-Dsf.e2e.nodeExecutable=<absolute node executable>` selects a particular Node installation. Node runtime absence or incompatibility fails report generation and preserves raw evidence. A failed report render never changes an execution failure into a passing result. An exact local LWC/Jest runner still uses the versions authorized in its component plan; upgrading the framework does not silently rewrite consumer plans or applications.

## Jackson 3 compatibility

Jackson imports and dependency coordinates use `tools.jackson`; annotations retain their official `com.fasterxml.jackson.annotation` namespace. Java time support is built into Jackson 3. The JSON mapper explicitly preserves the version-1 serialization contract: declaration-order records, sorted map keys, ISO timestamps, enum names and optional primitive defaults. Unknown properties remain rejected. A fixture produced by the previously published Jackson 2 CLI verifies that saved case recipes keep their exact bytes and checksum after migration.

## JUnit 6 lifecycle compatibility

JUnit automatically closes `AutoCloseable` values held in its extension store. The framework retains its explicit finalization after user teardown and makes session cleanup idempotent, so automatic fallback cleanup does not close a browser twice. The existing dependency-chain regression verifies setup, teardown, browser closure and dependent-case ordering; a direct repeat-close check protects the same contract.

When deliberately upgrading dependencies, rerun the full Maven checks, Node adapter checks, recipe compatibility checks and a real offline Allure browser inspection. See [verification](VERIFICATION.md) for recorded outcomes. Historical verification receipts retain the versions actually used for those measurements.