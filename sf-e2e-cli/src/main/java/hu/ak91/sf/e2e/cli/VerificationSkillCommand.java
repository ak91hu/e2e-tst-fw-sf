package hu.ak91.sf.e2e.cli;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Generates a consumer-local agent entry point from a preflighted suite, without embedding its data. */
@Command(name="verification-skill",mixinStandardHelpOptions=true,description="Generate a private application verification SKILL.md from a valid consumer suite; no UI or model.")
public final class VerificationSkillCommand implements Callable<Integer> {
    @Mixin SuiteCommands.Inputs input;
    @Option(names="--output-dir",required=true,description="New consumer-local skill directory.") Path output;
    @Option(names="--check",description="Verify an existing skill's input fingerprints instead of generating it.") boolean check;
    public Integer call() throws Exception {
        Failure.require(input.shard==null && input.lastFailed==null && input.coverage==null && input.tags.isEmpty() && input.datasets.isEmpty(),"CASE_INVALID","A verification skill describes the complete suite, not a filtered subset");
        var config=input.options.config("deterministic");var prepared=input.prepare(config);var suite=CaseSuite.load(input.file);
        var receipt=Map.of("schemaVersion",1,"suite",input.file.toAbsolutePath().normalize().toString(),"suiteSourceHash",SuiteSelection.sourceHash(suite),"config",input.options.file.toAbsolutePath().normalize().toString(),"configHash",Json.hash(config),
            "datasets",prepared.stream().map(p -> Map.of("id",p.row().id(),"recipeHash",Json.hash(p.recipe()),"paramsHash",Json.hash(p.params()))).toList());
        if(check) {Failure.require(Json.hash(Json.read(output.resolve("verification.json"),Map.class)).equals(Json.hash(receipt)),"SELECTION_STALE","Verification skill inputs changed; review and regenerate");System.out.println("Verification skill fingerprints passed");return 0;}
        Failure.require(!Files.exists(output),"POLICY_DENIED","Verification skill output already exists");
        Path directory=output.toAbsolutePath().normalize();String owner=UUID.randomUUID().toString();PrivateArtifacts.create(directory,owner,168);
        Json.write(directory.resolve("verification.json"),receipt);
        String flags=" --config "+quote(input.options.file.toAbsolutePath().normalize().toString())+" --suite "+quote(input.file.toAbsolutePath().normalize().toString());
        String content="""
            ---
            name: sf-e2e-verify
            description: Verify this consuming application's reviewed UI workflows with the Salesforce Java framework and unchanged deterministic expectations.
            ---
            # Application verification

            ## Preconditions

            Read `verification.json` and the framework's `guide cases` and `guide autonomous`.
            The recorded suite and configuration hashes identify the reviewed inputs. Regenerate this skill after changing those inputs.
            The host must provide Java 21+, the matching CLI JAR, authorized persona/session references and repeatable consumer test data.
            Verify all manifest, config, recipe and parameter fingerprints with the offline check below. A mismatch requires review and regeneration.
            All recipes, locators, runtime values and expected outcomes remain consumer-owned. No values or credentials are embedded here.

            ## Offline preflight

            Run this PowerShell command from the consuming project (adjust only the installed CLI path):

            ```powershell
            java -jar sf-e2e-cli/target/sf-e2e.jar verification-skill%s --output-dir %s --check
            java -jar sf-e2e-cli/target/sf-e2e.jar validate-suite%s
            ```

            A failed/empty preflight cannot become a successful verification. Inspect the reported code and fix the consumer input or map.

            ## Execute and assess

            Execute only within the user's authorized environment and mutation scope:

            ```powershell
            java -jar sf-e2e-cli/target/sf-e2e.jar run-suite%s --strict-locators
            ```

            Saved recipes run without a model. Keep all assertions unchanged. Report every selected dataset status, run path and exit code.
            Verify the produced report with `report verify --run <run-directory>` and inspect its evidence/quality failures.
            A valid report with failed rows remains failed. Missing/skipped/blocked rows cannot be summarized as passed.

            ## Diagnose failures

            Use `guide autonomous` for explicit `--last-failed` reruns and dependency-preserving shards.
            Never automatically retry a dispatched mutation, replace an expected outcome, heal an ambiguous identity or write to strict replay storage.
            If intentional UI identity changed, review and relearn separately under the mapping profile, then regenerate this skill.
            Preserve private params, encrypted sessions and raw repro bundles locally; publish only verified masked evidence.
            """.formatted(flags,quote(directory.toString()),flags,flags);
        Files.writeString(directory.resolve("SKILL.md"),content);
        PrivateArtifacts.manifest(directory,owner,directory.resolve("SKILL.md"),1024*1024);
        PrivateArtifacts.manifest(directory,owner,directory.resolve("verification.json"),1024*1024);
        System.out.println("Verification skill: "+directory);return 0;
    }
    static String quote(String value) {return "'"+value.replace("'","''")+"'";}
}
