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
    @Test void missingLearnInputsFailBeforeCredentials() {
        assertEquals(2,run("learn-case","--config","../sf-e2e.yaml","--locator-mode","read-write","--output",root.resolve("new.json").toString()));
        assertTrue(errors.toString().contains("Supply exactly one"));assertFalse(errors.toString().contains("credential"));
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
