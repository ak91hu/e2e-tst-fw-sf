package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MaintenanceCommandsTest {
    @TempDir Path root;
    private final StringWriter output=new StringWriter(),errors=new StringWriter();
    private int run(String... args) {var cli=SfE2eCli.commandLine();cli.setOut(new PrintWriter(output));cli.setErr(new PrintWriter(errors));return cli.execute(args);}
    private CaseRecipe baseline() {return new CaseRecipe(1,"historical-case",Json.hash("fixture"),"fixture","/app",List.of(
        new CaseRecipe.Step("fill","shared","field",null,"input",null),new CaseRecipe.Step("expectValue","shared","field",null,"expected",null)));}
    private Path recipe() {Path file=root.resolve("baseline.json");baseline().save(file);return file;}
    private Path changes() {
        Path file=root.resolve("changes.json");Json.write(file,new CaseMaintenance.Changes(1,Json.hash(baseline()),List.of(new CaseMaintenance.Insertion(2,List.of(
            new CaseSpec.Step("fill","New field",null,null,"newInput",null))))));return file;
    }
    @Test void offlinePlanNeedsNoConfigurationBrowserCredentialsOrWrites() throws Exception {
        Path baseline=recipe(),patch=changes();byte[] before=Files.readAllBytes(baseline);long count;
        try(var files=Files.list(root)) {count=files.count();}
        assertEquals(0,run("maintenance-plan","--file",baseline.toString(),"--changes",patch.toString()));
        var plan=Json.MAPPER.readTree(output.toString());assertEquals("PLANNED",plan.path("status").asString());assertFalse(plan.path("uiVerified").asBoolean());
        assertTrue(plan.path("needsLearning").asBoolean());assertEquals("historical-case",plan.path("caseId").asString());
        assertEquals(3,plan.path("steps").size());assertEquals(2,plan.path("steps").path(2).path("baselineStep").asInt());
        assertEquals(Json.hash(baseline()),plan.path("baselineChecksum").asString());assertArrayEquals(before,Files.readAllBytes(baseline));
        try(var files=Files.list(root)) {assertEquals(count,files.count());}
    }
    @Test void locatorOnlyPlanReportsNoLearning() throws Exception {
        assertEquals(0,run("maintenance-plan","--file",recipe().toString()));
        var plan=Json.MAPPER.readTree(output.toString());assertFalse(plan.path("needsLearning").asBoolean());assertEquals(2,plan.path("steps").size());
    }
    @Test void relatedRecipesExposeOnlyPotentialSharedDependencies() throws Exception {
        var related=new CaseRecipe(1,"related",baseline().namespaceHash(),"fixture","/other",List.of(new CaseRecipe.Step("expectVisible","shared","field",null,null,null)));
        Path file=root.resolve("related.json");related.save(file);
        assertEquals(0,run("maintenance-plan","--file",recipe().toString(),"--related-recipe",file.toString()));
        var shared=Json.MAPPER.readTree(output.toString()).path("potentialSharedCases");assertEquals(1,shared.size());assertEquals("related",shared.path(0).path("caseId").asString());
        assertTrue(shared.toString().contains("shared/field"));
    }
    @Test void optionalParameterValidationNeverEchoesValues() {
        Path values=root.resolve("values.json");Json.write(values,Map.of("input","PRIVATE_SENTINEL","expected","PRIVATE_SENTINEL","newInput","PRIVATE_SENTINEL"));
        assertEquals(0,run("maintenance-plan","--file",recipe().toString(),"--changes",changes().toString(),"--params",values.toString()));
        assertFalse(output.toString().contains("PRIVATE_SENTINEL"));assertTrue(output.toString().contains("newInput"));
    }
    @Test void stalePatchIsRejectedBeforeConfigurationOrAuthentication() {
        Path patch=root.resolve("stale.json");Json.write(patch,new CaseMaintenance.Changes(1,Json.hash("other"),List.of()));
        assertEquals(2,run("maintain-case","--file",recipe().toString(),"--changes",patch.toString(),"--output",root.resolve("new.json").toString(),"--config",root.resolve("missing.yaml").toString()));
        assertTrue(errors.toString().contains("CASE_MAINTENANCE_CONFLICT"));assertFalse(Files.exists(root.resolve("TST_new.json")));
    }
    @Test void malformedReplacementPatchCannotRewriteAnExistingOracle() throws Exception {
        Path patch=root.resolve("replacement.json");Files.writeString(patch,"{\"schemaVersion\":1,\"baselineChecksum\":\""+Json.hash(baseline())+"\",\"insertions\":[],\"replacements\":[]}");
        assertEquals(2,run("maintenance-plan","--file",recipe().toString(),"--changes",patch.toString()));assertTrue(errors.toString().contains("CASE_MAINTENANCE_INVALID"));
    }
    @Test void missingNewParameterFailsBeforeConfigurationAndLeavesBaselineUntouched() throws Exception {
        Path baseline=recipe(),values=root.resolve("values.json");Json.write(values,Map.of("input","x","expected","x"));byte[] before=Files.readAllBytes(baseline);
        assertEquals(2,run("maintain-case","--file",baseline.toString(),"--changes",changes().toString(),"--params",values.toString(),"--output",root.resolve("new.json").toString(),"--config",root.resolve("missing.yaml").toString()));
        assertTrue(errors.toString().contains("CASE_PARAMETER_REQUIRED"));assertArrayEquals(before,Files.readAllBytes(baseline));
    }
    @Test void existingOutputIsPreservedBeforeOpeningAnySession() throws Exception {
        Path baseline=recipe(),values=root.resolve("values.json");Json.write(values,Map.of("input","x","expected","x"));byte[] before=Files.readAllBytes(baseline);
        assertEquals(2,run("maintain-case","--file",baseline.toString(),"--params",values.toString(),"--output",baseline.toString(),"--config",root.resolve("missing.yaml").toString()));
        assertTrue(errors.toString().contains("CASE_RECIPE_EXISTS"));assertArrayEquals(before,Files.readAllBytes(baseline));
    }
    @Test void newCommandsHaveConfigFreeHelp() {
        assertEquals(0,run("maintenance-plan","--help"));assertEquals(0,run("maintain-case","--help"));
        assertTrue(output.toString().contains("--related-recipe"));assertTrue(output.toString().contains("--changes"));
    }
}
