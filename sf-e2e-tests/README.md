# Consumer test workspace

[Project README](../README.md) · [Documentation index](../docs/README.md) · [Agent instructions](../AGENTS.md)

## Scope and audience

This module intentionally contains no Salesforce test cases, sample business scenarios or design catalogue. It documents a consumer integration boundary. The reusable framework distribution has no target org or customer-specific tests.

Framework-maintenance changes must keep `src/test/java` empty. Internal regression checks belong to the implementation module that owns the behavior and use synthetic fixtures or local mock services. Org-specific configuration, business cases, values, maps and credentials belong to the separate consuming project. Maintainers should follow [the source/test map and development workflow](../docs/DEVELOPMENT.md).

## Consumer JSON and text cases

For project-supplied JSON/text cases, use the [learn/discover/replay workflow](../docs/CASE_WORKFLOWS.md). It saves private recipes outside this module and needs no generated Java business case or recompilation for later replay. Learning executes supplied actions and verifies the supplied outcome; subsequent replay independently verifies current state without a model.

## Consumer Java integration

In a consuming project's own test source set, define cases with `@Test`, `@SfCase` and `@ExtendWith(SfE2eExtension.class)`. Access mapped UI through `SfTestContext.page(pageId)` or its page registry. Page Object Model is the default; compose project-owned page/component classes over these APIs.

Use the `TST_` prefix for new Java class/method names and `@SfCase` IDs/names. New CLI-generated case IDs and recipe filenames receive the prefix automatically; existing cases remain compatible. See [Page Object Model and naming](../docs/PAGE_OBJECT_MODEL.md).

Compile the consuming project's cases, put its compiled test classes on the classpath together with the framework CLI jar, and pass each fully qualified class using `--test-class`. Dependencies are opt-in. Authentication is framework lifecycle, not a bundled setup business case. This framework module has no Failsafe binding for live consumer cases; use the explicit coordinator/CLI path. A class ending in `IT` is not selected by normal Surefire unit-test naming.

## Coordinated execution contract

Use the CLI or `RunCoordinator`. Direct JUnit/IDE invocation is rejected before user `@BeforeAll`, preserving dependency planning, profiles and the org lock. Each selected class executes as one contiguous group; `@BeforeAll`/`@AfterAll` and `PER_CLASS` state retain their normal scope.

The per-case context stays open during user `@AfterEach`. Teardown and later class/container failures affect the final result and block dependents. Method order comes from `@SfCase.dependencies`; conflicting orderers and dependencies requiring A -> B -> A class interleaving fail at planning time. Read [the lifecycle review](../docs/TEST_STRUCTURE_REVIEW.md) for current rules and [the development guide](../docs/DEVELOPMENT.md#execution-and-ownership-contracts) for the maintenance boundaries.
