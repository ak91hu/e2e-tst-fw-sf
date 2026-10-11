package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CaseCommandsTest {
    @TempDir Path root;
    private final StringWriter errors=new StringWriter();
    private int run(String... args) { var cli=SfE2eCli.commandLine();cli.setErr(new PrintWriter(errors));return cli.execute(args); }
    private CaseRecipe verifiedRecipe() {
        return new CaseRecipe(1,"TST_publication",Json.hash("fixture"),"fixture-engine","/app",
            List.of(new CaseRecipe.Step("expectRoute",null,null,"/app",null,null)));
    }
    @Test void missingSpecParametersProduceOrderedBlockersAndHtmlBeforeAuthentication() throws Exception {
        Path config=root.resolve("project.yaml"),spec=root.resolve("spec.json");
        Files.writeString(config,"schemaVersion: 1\nprojectId: preflight-fixture\nprofile: mapping\norg:\n  baseUrl: https://fixture.example\nlocators:\n  mode: read-write\nreport:\n  root: reports\n");
        Json.write(spec,new CaseSpec("TST_missing","/app",List.of(new CaseSpec.Step("fill","Input",null,null,"input",null),new CaseSpec.Step("expectValue","Input",null,null,"expected",null))));
        assertNotEquals(0,run("learn-case","--config",config.toString(),"--spec",spec.toString(),"--output",root.resolve("TST_missing.json").toString()));
        try(var files=Files.list(root.resolve("reports"))) {
            Path report=files.findFirst().orElseThrow();String html=Files.readString(report.resolve("preflight.html"));
            assertTrue(html.contains("Step 1: fill"));assertTrue(html.contains("Step 2: expectValue"));assertTrue(html.contains("CASE_PARAMETER_REQUIRED"));
            assertTrue(Files.readString(report.resolve("results.html")).contains("NOT EXECUTED"));assertFalse(Files.exists(root.resolve("TST_missing.json")));
        }
    }
    @Test void lateFinalizationFailureCannotLeaveSuccessfulFallbackHtml() throws Exception {
        Path config=root.resolve("project.yaml");Files.writeString(config,"schemaVersion: 1\nprojectId: late-fixture\nreport:\n  root: reports\n");
        var policy=FrameworkConfig.load(config,Map.of());
        assertThrows(Failure.class,() -> PreparedRun.execute(policy,"synthetic finalization",preparation -> {
            preparation.ready();hu.ak91.sf.e2e.reporting.HtmlSummary.write(preparation.root,"synthetic", "PASSED",List.of(Map.of("step",1,"operation","expectVisible")),"Synthetic summary before a late finalization failure");
            throw new Failure("ENGINE_FAILURE","PRIVATE_SENTINEL");
        }));
        try(var files=Files.list(root.resolve("reports"))) {
            String html=Files.readString(files.findFirst().orElseThrow().resolve("results.html"));
            assertTrue(html.contains("<strong>BLOCKED</strong>"));assertTrue(html.contains("expectVisible"));assertTrue(html.contains("ENGINE_FAILURE"));assertFalse(html.contains("PRIVATE_SENTINEL"));
        }
    }
    @Test void failedSessionCleanupCannotPublishAVerifiedRecipe() {
        Path output=root.resolve("TST_cleanup.json");var phases=new ArrayList<String>();
        var failure=new Failure("ENGINE_FAILURE","Synthetic cleanup failure");
        AutoCloseable session=() -> {phases.add("close");throw failure;};
        assertSame(failure,assertThrows(Failure.class,() -> {
            var recipe=CaseCommands.verifiedAfterClose(session,() -> {phases.add("verify");return verifiedRecipe();});
            CaseCommands.publishVerifiedOutput(output,recipe,Map.of(),false,() -> phases.add("report"));
        }));
        assertEquals(List.of("verify","close"),phases);assertFalse(Files.exists(output));
    }
    @Test void failedReportCannotPublishRecipeOrExtractedParameters() throws Exception {
        Path output=root.resolve("TST_report.json");var failure=new Failure("REPORT_GENERATION_FAILED","Synthetic report failure");
        var recipe=CaseCommands.verifiedAfterClose(() -> {},this::verifiedRecipe);
        assertSame(failure,assertThrows(Failure.class,() -> CaseCommands.publishVerifiedOutput(output,recipe,Map.of("input","PRIVATE_INPUT"),true,() -> {throw failure;})));
        assertFalse(Files.exists(output));assertFalse(Files.exists(root.resolve("TST_report.json.params.local.json")));
    }
    @Test void failedVerificationRetainsItsFailureWhenSessionCleanupAlsoFails() {
        var primary=new Failure("ASSERTION_FAILED","Synthetic wrong expectation");var cleanup=new Failure("ENGINE_FAILURE","Synthetic cleanup failure");
        AutoCloseable session=() -> {throw cleanup;};
        var failure=assertThrows(Failure.class,() -> CaseCommands.verifiedAfterClose(session,() -> {throw primary;}));
        assertSame(primary,failure);assertArrayEquals(new Throwable[]{cleanup},failure.getSuppressed());
    }
    @Test void successfulPublicationFollowsSessionCleanupAndReportFinalization() throws Exception {
        Path output=root.resolve("TST_success.json");var phases=new ArrayList<String>();
        var recipe=CaseCommands.verifiedAfterClose(() -> phases.add("close"),() -> {phases.add("verify");return verifiedRecipe();});
        CaseCommands.publishVerifiedOutput(output,recipe,Map.of("input","PRIVATE_INPUT"),true,() -> {
            phases.add("report");assertFalse(Files.exists(output));assertFalse(Files.exists(root.resolve("TST_success.json.params.local.json")));
        });
        assertEquals(List.of("verify","close","report"),phases);assertEquals(recipe,CaseRecipe.load(output));
        assertEquals(Map.of("input","PRIVATE_INPUT"),Json.read(root.resolve("TST_success.json.params.local.json"),Map.class));
    }
    @Test void reportFailureRetainsThePrimaryWorkflowFailureWithoutOpeningABrowser() throws Exception {
        String reference="E2E_REVIEW_MISSING_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        assertNull(System.getenv(reference));Path config=root.resolve("project.yaml");
        Files.writeString(config,"schemaVersion: 1\nprojectId: final-review-fixture\nprofile: mapping\norg:\n  baseUrl: https://fixture.example\nauth:\n  mode: storage-state\npersonas:\n  default:\n    usernameRef: E2E_UNUSED_REVIEW_USER\n    storageStateRef: "+reference+"\nlocators:\n  dir: maps\n  mode: read-write\nreport:\n  root: reports\nretention:\n  journalDir: journals\n");
        var policy=FrameworkConfig.load(config,Map.of());String previous=System.getProperty("sf.e2e.nodeExecutable");
        Path output=root.resolve("TST_unpublished.json");
        try {
            System.setProperty("sf.e2e.nodeExecutable",root.resolve("missing-node").toString());
            var failure=assertThrows(Failure.class,() -> CaseCommands.session(policy,new Persona("default"),"TST_fixture","/app",Map.of(),"learning",output,
                (engine,store,model,namespace,deadline,secrets,events) -> {throw new AssertionError("Missing fixture credentials must prevent a browser/model session");}));
            assertEquals("AUTH_CREDENTIAL_UNAVAILABLE",failure.code());assertFalse(Files.exists(output));
            assertTrue(Arrays.stream(failure.getSuppressed()).anyMatch(error -> error instanceof Failure secondary && secondary.code().equals("REPORT_RUNTIME_UNAVAILABLE")));
            try(var reports=Files.list(root.resolve("reports"))) {
                var state=Json.read(reports.findFirst().orElseThrow().resolve("case-report.json"),Map.class);
                assertEquals("FAILED",state.get("status"));assertEquals("AUTH_CREDENTIAL_UNAVAILABLE",state.get("code"));
            }
        } finally {if(previous==null) System.clearProperty("sf.e2e.nodeExecutable");else System.setProperty("sf.e2e.nodeExecutable",previous);}
    }
    @Test void concurrentPublicationCannotOverwriteAnotherVerifiedRecipe() throws Exception {
        Path output=root.resolve("TST_shared.json");var barrier=new java.util.concurrent.CyclicBarrier(2);
        var first=verifiedRecipe();var second=new CaseRecipe(1,"TST_other",first.namespaceHash(),first.engineVersion(),first.startRoute(),first.steps());
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var outcomes=new ArrayList<java.util.concurrent.Future<String>>();
            for(var recipe:List.of(first,second)) outcomes.add(workers.submit(() -> {
                try {
                    CaseCommands.publishVerifiedOutput(output,recipe,Map.of(),false,() -> {
                        try {barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception failure) {throw new AssertionError(failure);}
                    });return "PUBLISHED";
                } catch(Failure failure) {return failure.code();}
            }));
            var codes=List.of(outcomes.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS),outcomes.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,codes.stream().filter("PUBLISHED"::equals).count());
            assertTrue(codes.stream().allMatch(Set.of("PUBLISHED","CASE_OUTPUT_BUSY","CASE_RECIPE_EXISTS")::contains));
            assertTrue(List.of(first,second).contains(CaseRecipe.load(output)));byte[] preserved=Files.readAllBytes(output);
            assertEquals("CASE_RECIPE_EXISTS",assertThrows(Failure.class,() -> CaseCommands.publishVerifiedOutput(output,first,Map.of(),false,() -> fail("Existing output must reject publication before report work"))).code());
            assertArrayEquals(preserved,Files.readAllBytes(output));
        }
    }
    @Test void offlineValidationNeedsNoConfigurationAndReportsIntentWithoutClaimingUiVerification() throws Exception {
        var spec=root.resolve("spec.json");var params=root.resolve("params.json");
        Json.write(spec,new CaseSpec("input","/app",List.of(new CaseSpec.Step("fill","Field",null,null,"input",null),new CaseSpec.Step("expectValue","Field",null,null,"expected",null))));
        Json.write(params,Map.of("input","PRIVATE_INPUT","expected","PRIVATE_EXPECTATION"));
        var output=new StringWriter();var cli=SfE2eCli.commandLine();cli.setOut(new PrintWriter(output));cli.setErr(new PrintWriter(errors));
        assertEquals(0,cli.execute("validate-case","--spec",spec.toString(),"--params",params.toString()));
        var summary=Json.MAPPER.readTree(output.toString());assertEquals("VALID",summary.path("status").asString());
        assertFalse(summary.path("uiVerified").asBoolean());assertEquals(1,summary.path("assertions").asInt());
        assertEquals(List.of("expected","input"),Json.MAPPER.treeToValue(summary.path("requiredParameters"),List.class));
        assertFalse(output.toString().contains("PRIVATE_"));assertFalse(Files.exists(root.resolve(".sf-e2e")));
    }
    @Test void offlineValidationNamesMissingParametersAndRejectsInvalidOperationValues() {
        var spec=root.resolve("spec.json");var params=root.resolve("params.json");
        Json.write(spec,new CaseSpec("toggle","/app",List.of(new CaseSpec.Step("setChecked","Toggle",null,null,"checked",null),new CaseSpec.Step("expectChecked","Toggle",null,null,"expected",null))));
        assertEquals(2,run("validate-case","--spec",spec.toString()));assertTrue(errors.toString().contains("checked, expected"));
        Json.write(params,Map.of("checked","yes","expected","true"));
        assertEquals(2,run("validate-case","--spec",spec.toString(),"--params",params.toString()));assertTrue(errors.toString().contains("Invalid checked state"));
        assertFalse(errors.toString().contains("credential"));
    }
    @Test void plansRemainEditableSpecificationsWithSeparateValuesAndExistingFilesArePreserved() {
        var output=root.resolve("TST_plan.local.json");var values=root.resolve("TST_plan.local.json.params.local.json");
        var spec=new CaseSpec("plan","/app",List.of(new CaseSpec.Step("expectText","Result",null,null,"expected",null)));
        assertEquals(0,CaseCommands.savePlan(output,spec,Map.of("expected","PRIVATE_EXPECTATION")));
        assertEquals(spec,Json.read(output,CaseSpec.class));assertEquals(Map.of("expected","PRIVATE_EXPECTATION"),Json.read(values,Map.class));
        assertFalse(Json.string(Json.read(output,Map.class)).contains("PRIVATE_EXPECTATION"));assertThrows(Failure.class,() -> CaseRecipe.load(output));
        assertEquals("CASE_RECIPE_EXISTS",assertThrows(Failure.class,() -> CaseCommands.savePlan(output,spec,Map.of("expected","changed"))).code());
        assertEquals(Map.of("expected","PRIVATE_EXPECTATION"),Json.read(values,Map.class));
    }
    @Test void invalidPlanInputsAndExistingSidecarsFailBeforeModelCredentials() throws Exception {
        var text=root.resolve("intent.txt");Files.writeString(text,"Verify the result is ${expected}.");var output=root.resolve("plan.json");
        assertEquals(2,run("plan-case","--instructions",text.toString(),"--id","plan","--route","//foreign.example/app","--output",output.toString()));
        assertFalse(errors.toString().contains("credential"));
        var sidecar=root.resolve("TST_plan.json.params.local.json");Json.write(sidecar,Map.of("preserved",true));
        assertEquals(2,run("plan-case","--instructions",text.toString(),"--id","plan","--route","/app","--output",output.toString()));
        assertTrue(errors.toString().contains("CASE_RECIPE_EXISTS"));assertFalse(Files.exists(root.resolve("TST_plan.json")));
        assertEquals(Map.of("preserved",true),Json.read(sidecar,Map.class));
    }
    @Test void missingLearnInputsFailBeforeCredentials() {
        assertEquals(2,run("learn-case","--config","../sf-e2e.yaml","--locator-mode","read-write","--output",root.resolve("new.json").toString()));
        assertTrue(errors.toString().contains("Supply exactly one"));assertFalse(errors.toString().contains("credential"));
    }
    @Test void reproCharterRejectsChangedIntentBeforeCredentialsOrUi() {
        var oracle=new CaseSpec.Step("expectText","Result",null,null,"expected",null);Path charter=root.resolve("charter.json"),spec=root.resolve("spec.json"),values=root.resolve("values.json");
        Json.write(charter,new ExplorationCharter(1,"repro","Verify supplied result",List.of("/app"),Set.of("click"),Set.of("Submit"),List.of(oracle),1,5));
        Json.write(spec,new CaseSpec("repro","/app",List.of(new CaseSpec.Step("click","Delete everything",null,null,null,null),oracle)));Json.write(values,Map.of("expected","INDEPENDENT_EXPECTATION"));
        assertEquals(2,run("learn-case","--config","../sf-e2e.yaml","--locator-mode","read-write","--spec",spec.toString(),"--params",values.toString(),"--charter",charter.toString(),"--output",root.resolve("new.json").toString()));
        assertTrue(errors.toString().contains("MODEL_OUTPUT_INVALID"));assertFalse(errors.toString().contains("credential"));assertFalse(Files.exists(root.resolve("TST_new.json")));
    }
    @Test void malformedSpecAndMissingExpectedParametersFailBeforeCredentials() {
        var file=root.resolve("spec.json");Json.write(file,new CaseSpec("case","/app",List.of(new CaseSpec.Step("expectText","Result",null,null,"expected",null))));
        assertEquals(2,run("learn-case","--config","../sf-e2e.yaml","--locator-mode","read-write","--spec",file.toString(),"--output",root.resolve("new.json").toString()));
        assertTrue(errors.toString().contains("CASE_PARAMETER_REQUIRED"));
        Json.write(file,Map.of("id","case","startRoute","/app","steps",List.of(),"unknown",true));
        assertEquals(2,run("learn-case","--config","../sf-e2e.yaml","--locator-mode","read-write","--spec",file.toString(),"--output",root.resolve("new.json").toString()));
        assertTrue(errors.toString().contains("CASE_INVALID"));
    }
    @Test void existingRecipesAndInvalidDiscoveryBudgetsAreRejectedBeforeAuthentication() {
        Path existing=root.resolve("existing.json");Json.write(existing,Map.of("preserved",true));
        assertEquals(2,run("discover-ui","--config","../sf-e2e.yaml","--locator-mode","read-write","--id","ui","--route","/app","--output",existing.toString()));
        assertTrue(errors.toString().contains("CASE_RECIPE_EXISTS"));
        assertEquals(2,run("discover-ui","--config","../sf-e2e.yaml","--locator-mode","read-write","--id","ui","--route","/app","--max-pages","0","--output",root.resolve("new.json").toString()));
        assertTrue(errors.toString().contains("CASE_INVALID"));
    }
    @Test void replayRejectsAiProfileAndWrongNamespaceBeforeModelOrAuthentication() {
        Path file=root.resolve("case.json");new CaseRecipe(1,"case",Json.hash("different-persona"),"engine","/app",List.of(new CaseRecipe.Step("expectVisible","p","key",null,null,null))).save(file);
        assertEquals(2,run("replay-case","--config","../sf-e2e.yaml","--profile","mapping","--file",file.toString()));
        assertTrue(errors.toString().contains("requires the deterministic profile"));
        assertEquals(2,run("replay-case","--config","../sf-e2e.yaml","--file",file.toString()));assertTrue(errors.toString().contains("CASE_WRONG_CONTEXT"));
    }
    @Test void prefixedOutputsAreIdempotentAndProtectedBeforeCredentials() {
        Path requested=root.resolve("new.json"),named=root.resolve("TST_new.json");
        assertEquals(named,CaseCommands.preparedOutput(requested));assertEquals(named,CaseCommands.preparedOutput(named));
        Json.write(named,Map.of("preserved",true));
        assertEquals(2,run("discover-ui","--config","../sf-e2e.yaml","--locator-mode","read-write","--id","flow","--route","/app","--output",requested.toString()));
        assertTrue(errors.toString().contains("CASE_RECIPE_EXISTS"));assertEquals(Map.of("preserved",true),Json.read(named,Map.class));assertFalse(Files.exists(requested));
    }
    @Test void invalidGeneratedNamesAndPageSwitchParametersFailBeforeCredentials() {
        assertEquals(2,run("discover-ui","--config","../sf-e2e.yaml","--locator-mode","read-write","--id","TST_","--route","/app","--output",root.resolve("new.json").toString()));
        assertTrue(errors.toString().contains("CASE_INVALID"));
        var spec=new CaseSpec("pages","/app",List.of(new CaseSpec.Step("switchPage",null,null,null,"handle",null),new CaseSpec.Step("expectRoute",null,null,"/app",null,null)));
        assertDoesNotThrow(() -> CaseCommands.validateSpecParams(spec,Map.of("handle","main")));
        assertThrows(Failure.class,() -> CaseCommands.validateSpecParams(spec,Map.of("handle","../foreign")));
    }
}
