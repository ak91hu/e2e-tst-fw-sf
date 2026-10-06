# User tests

This module intentionally contains no Salesforce test cases, sample scenarios or design catalogue.
For cases supplied as JSON/text, use the [learn/discover/replay CLI workflow](../docs/CASE_WORKFLOWS.md).
It saves private recipes outside this module and needs no Java case generation or recompilation.
Add your own Java cases under `src/test/java` when needed. Use `@Test`, `@SfCase` and
`@ExtendWith(SfE2eExtension.class)`, and access the mapped UI through `sf.page(pageId)`.
Page Object Model is the default; compose project-owned page/component classes over this API.
Name new Java classes/methods and `@SfCase` IDs/names with the `TST_` prefix. New CLI-generated
case IDs and recipe filenames receive that prefix automatically. Existing cases remain compatible.
See [POM and naming](../docs/PAGE_OBJECT_MODEL.md).
Dependencies are opt-in; authentication is framework lifecycle, not a bundled setup case.

For explicit CLI execution, compile your cases, put `target/test-classes` on the classpath together
with the CLI jar, and pass each fully qualified class using `--test-class`. Use a class name ending
in `IT` if you want normal credential-free Maven checks to skip live cases.

Use the CLI or `RunCoordinator` for execution. Direct JUnit/IDE invocation is rejected before
user `@BeforeAll`, so dependencies, profiles and the org lock cannot be bypassed. Each class
runs once; `@BeforeAll`/`@AfterAll` and `PER_CLASS` instance state retain their normal scope.
The per-case context stays open during `@AfterEach`, and teardown failures affect the report
and block dependents. Method order comes from `@SfCase.dependencies`; conflicting orderers
and dependencies requiring A -> B -> A class interleaving fail at planning time.
