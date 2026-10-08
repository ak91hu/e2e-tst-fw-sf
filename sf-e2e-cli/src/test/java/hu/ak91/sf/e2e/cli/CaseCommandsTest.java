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
