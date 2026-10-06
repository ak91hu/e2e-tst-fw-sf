package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.reporting.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SuiteCommandsTest {
    @TempDir Path root;
    Path configFile,manifest;
    void prepare() throws Exception {
        configFile=root.resolve("project.yaml");Files.writeString(configFile,"schemaVersion: 1\nprojectId: suite-checks\nprofile: deterministic\norg:\n  baseUrl: https://application.example\nreport:\n  root: report\nlocators:\n  dir: locators\n");
        var config=FrameworkConfig.load(configFile,Map.of("profile","deterministic"));String namespace=LocatorStore.namespace(config,Persona.DEFAULT);
        var store=new LocatorStore(Path.of(config.locators().dir()),namespace,"read-write");store.putAll(List.of(new LocatorEntry("p","heading","https://application.example/app","engine",SemanticTarget.role("heading","Ready","page"),Map.of(),List.of(),1,List.of())));
        new CaseRecipe(1,"presence",Json.hash(namespace),"engine","/app",List.of(new CaseRecipe.Step("expectVisible","p","heading",null,null,null))).save(root.resolve("recipe.json"));
        manifest=root.resolve("suite.json");Json.write(manifest,new CaseSuite(1,"regression",List.of(new CaseSuite.Row("first","recipe.json",null,null,List.of("smoke")),new CaseSuite.Row("second","recipe.json",null,null,List.of("regression")))));
    }
    void state(Path child,SuiteCommands.Prepared prepared,String status,String code) {
        var s=new LinkedHashMap<String,Object>();s.put("schemaVersion",1);s.put("runId",child.getParent().getParent().getFileName()+"."+prepared.row().id());s.put("attemptId",UUID.randomUUID().toString());
        s.put("caseId",prepared.recipe().id());s.put("datasetId",prepared.row().id());s.put("persona","default");s.put("mode","replay");s.put("startedAt",Instant.now());s.put("completedAt",Instant.now());
        s.put("status",status);s.put("modelCalls",0);s.put("artifacts",List.of());if(code!=null)s.put("code",code);
        List<Map<String,Object>> events=List.of();
        if(status.equals("PASSED")) {
            var evidence=new EvidenceService(child,s.get("runId").toString(),s.get("attemptId").toString(),() -> new ImageEngine(),Deadline.after(Duration.ofSeconds(5)),new SecretRegistry());
            evidence.checkpoint("case-start");evidence.checkpoint("test-completion");s.put("artifacts",evidence.artifacts());events=evidence.events();
        }
        WorkflowReportExporter.write(child,s,events,false);
    }
    SuiteCommands.Run command(boolean failFast) {
        var cmd=new SuiteCommands.Run();var cli=new CommandLine(cmd);List<String> args=new ArrayList<>(List.of("--config",configFile.toString(),"--suite",manifest.toString()));if(failFast)args.add("--fail-fast");cli.parseArgs(args.toArray(String[]::new));return cmd;
    }
    Path runRoot() throws Exception { try(var paths=Files.list(root.resolve("report"))) {return paths.findFirst().orElseThrow();} }
    static class ImageEngine implements UiEngine {
        public byte[] captureMaskedPng(Deadline d) { try { var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8,8,java.awt.image.BufferedImage.TYPE_INT_RGB),"PNG",out);return out.toByteArray(); } catch(Exception e) {throw new RuntimeException(e);} }
        public void open(String r,Deadline d) {}public String route() {return "https://application.example/app";}public Observation observe(String c,Deadline d) {throw new UnsupportedOperationException();}
        public Observation.Node resolve(SemanticTarget t,Observation o) {throw new UnsupportedOperationException();}public void perform(Action a,Observation o,Deadline d) {throw new UnsupportedOperationException();}
        public Map<String,String> readVisibleFields(Deadline d) {return Map.of();}public void poll(Deadline d) {}public String capabilityVersion() {return "engine";}public void close() {}
    }
    @Test void preflightRejectsAnyInvalidSelectedRowBeforeCreatingAReportOrBrowser() throws Exception {
        prepare();Json.write(manifest,new CaseSuite(1,"regression",List.of(new CaseSuite.Row("first","recipe.json",null,null,List.of()),new CaseSuite.Row("bad","missing.json",null,null,List.of()))));
        var cmd=command(false);cmd.executor=(c,p,r) -> {fail("Browser execution must not start");return 0;};assertThrows(Failure.class,cmd::call);assertFalse(Files.exists(root.resolve("report")));
    }
    @Test void failedDatasetDoesNotStopIndependentRowsOrBecomeASuccessfulSuite() throws Exception {
        prepare();var cmd=command(false);List<String> executed=new ArrayList<>();cmd.executor=(c,p,path) -> {
            executed.add(p.row().id());state(path,p,p.row().id().equals("first")?"FAILED":"PASSED",p.row().id().equals("first")?"ASSERTION_FAILED":null);
            if(p.row().id().equals("first"))throw new Failure("ASSERTION_FAILED","Wrong business outcome");return 0;
        };
        assertEquals(1,cmd.call());assertEquals(List.of("first","second"),executed);Path report=runRoot();SuiteReportExporter.verify(report);SuiteReportExporter.rebuild(report);SuiteReportExporter.verify(report);
        var suite=Json.read(report.resolve("suite-report.json"),Map.class);assertEquals("FAILED",suite.get("status"));
        try(var files=Files.list(report.resolve("allure-results"))) {assertEquals(2,files.filter(p -> p.getFileName().toString().endsWith("-result.json")).count());}
    }
    @Test void failFastProducesSkippedRowsAndTamperedStatusCannotPassVerification() throws Exception {
        prepare();var cmd=command(true);List<String> executed=new ArrayList<>();cmd.executor=(c,p,path) -> {executed.add(p.row().id());state(path,p,"FAILED","ASSERTION_FAILED");throw new Failure("ASSERTION_FAILED","Expected outcome not met");};
        assertEquals(1,cmd.call());assertEquals(List.of("first"),executed);Path report=runRoot();SuiteReportExporter.verify(report);
        var skipped=Json.read(report.resolve("cases/second/case-report.json"),Map.class);assertEquals("SKIPPED",skipped.get("status"));
        var suite=Json.read(report.resolve("suite-report.json"),Map.class);suite.put("status","PASSED");suite.put("exitCode",0);Json.write(report.resolve("suite-report.json"),suite);
        assertEquals("REPORT_RUN_MISMATCH",assertThrows(Failure.class,() -> SuiteReportExporter.verify(report)).code());
    }
    @Test void tagsAndIdsSelectDatasetsAndUnknownOrEmptySelectionsCannotPass() throws Exception {
        prepare();assertEquals(0,new CommandLine(new SuiteCommands.Validate()).execute("--config",configFile.toString(),"--suite",manifest.toString(),"--include-tag","smoke","--dataset","first"));
        var suite=CaseSuite.load(manifest);assertEquals(1,suite.select(Set.of("smoke"),Set.of()).size());assertThrows(Failure.class,() -> suite.select(Set.of("unknown"),Set.of()));
    }
    @Test void cancellationSkipsRemainingRowsAndReturnsCancellationStatus() throws Exception {
        prepare();var cmd=command(false);List<String> executed=new ArrayList<>();cmd.executor=(c,p,path) -> {executed.add(p.row().id());throw new Failure("CANCELLED","Stopped");};
        assertEquals(130,cmd.call());assertEquals(List.of("first"),executed);SuiteReportExporter.verify(runRoot());
    }
    @Test void evidenceFailureAfterSuccessfulUiWorkCannotKeepAPassingDataset() throws Exception {
        prepare();var cmd=command(false);cmd.executor=(c,p,path) -> {state(path,p,"PASSED",null);if(p.row().id().equals("first"))throw new Failure("EVIDENCE_INCOMPLETE","Publication failed");return 0;};
        assertEquals(4,cmd.call());Path report=runRoot();SuiteReportExporter.verify(report);var saved=Json.read(report.resolve("cases/first/case-report.json"),Map.class);
        assertEquals("BLOCKED",saved.get("status"));assertEquals("PASSED",saved.get("businessStatus"));
    }
    @SuppressWarnings("unchecked") @Test void aggregateRejectsForeignRunCaseIdentityAndHiddenModelCalls() throws Exception {
        prepare();var cmd=command(false);cmd.executor=(c,p,path) -> {state(path,p,"PASSED",null);return 0;};assertEquals(0,cmd.call());
        Path report=runRoot(),child=report.resolve("cases/first");SuiteReportExporter.verify(report);
        String originalChild=Files.readString(child.resolve("case-report.json")),originalSuite=Files.readString(report.resolve("suite-report.json"));
        assertAll(java.util.stream.Stream.of("foreign-run","wrong-case","hidden-model").map(change -> (org.junit.jupiter.api.function.Executable) () -> {
            var saved=Json.MAPPER.readValue(originalChild,Map.class);var suite=Json.MAPPER.readValue(originalSuite,Map.class);
            var row=((List<Map<String,Object>>)suite.get("datasets")).getFirst();
            switch(change) {
                case "foreign-run" -> {
                    saved.put("runId","earlier-run.first");
                    for(var artifact:(List<Map<String,Object>>)saved.get("artifacts")) artifact.put("runId","earlier-run.first");
                }
                case "wrong-case" -> row.put("caseId","another-recipe");
                case "hidden-model" -> saved.put("modelCalls",1);
            }
            Json.write(child.resolve("case-report.json"),saved);row.put("checksum",Json.hash(saved));Json.write(report.resolve("suite-report.json"),suite);
            WorkflowReportExporter.verifyEvidence(child);
            assertEquals("REPORT_RUN_MISMATCH",assertThrows(Failure.class,() -> SuiteReportExporter.verify(report),change+" must fail aggregate verification").code());
        }));
    }
}
