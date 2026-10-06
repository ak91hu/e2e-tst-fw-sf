package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class AllureReportingTest {
    @TempDir Path root;
    private EvidenceService evidence() throws Exception {
        return new EvidenceService(root,"workflow","attempt",() -> {
            try { return new ReportIntegrityTest.Engine(); } catch(Exception e) { throw new RuntimeException(e); }
        },Deadline.after(Duration.ofSeconds(30)),new SecretRegistry());
    }
    private Map<String,Object> state(String status,List<EvidenceService.Artifact> artifacts) {
        Map<String,Object> state=new LinkedHashMap<>();state.put("schemaVersion",1);state.put("runId","workflow");state.put("attemptId","attempt");state.put("caseId","generic-case");
        state.put("mode","replay");state.put("status",status);state.put("startedAt",Instant.now().minusSeconds(1));state.put("completedAt",Instant.now());
        state.put("modelCalls",0);state.put("artifacts",artifacts);return state;
    }
    @Test void automaticStepScreenshotsAttachToPassedAllureStepsAndRealHtmlIsGenerated() throws Exception {
        Path older=root.resolve(".allure-render-"+UUID.randomUUID());Files.createDirectories(older);Files.writeString(older.resolve("diagnostic.txt"),"Keep previous render diagnostics");
        var evidence=evidence();evidence.checkpoint("case-start");
        evidence.event(Map.of("kind","case-replay-plan","steps",List.of(Map.of("kind","click","key","continue"),Map.of("kind","expectVisible","key","result"))));
        evidence.event(Map.of("kind","case-step-started","step",1,"action","click"));
        evidence.event(Map.of("kind","locator-action","action","click","page","generic","key","continue"));
        evidence.event(Map.of("kind","case-step-passed","step",1,"action","click"));
        evidence.event(Map.of("kind","case-step-started","step",2,"action","expectVisible"));
        evidence.event(Map.of("kind","locator-healed","reason","fingerprint","persisted",true));
        evidence.event(Map.of("kind","case-step-passed","step",2,"action","expectVisible"));evidence.checkpoint("test-completion");
        assertEquals(4,evidence.artifacts().size());
        WorkflowReportExporter.write(root,state("PASSED",evidence.artifacts()),evidence.events());WorkflowReportExporter.verify(root);
        var result=Json.MAPPER.readTree(Files.readString(root.resolve("allure-results/attempt-result.json")));
        assertEquals("passed",result.path("status").asText());assertEquals(2,result.path("steps").size());
        assertTrue(result.path("steps").get(0).path("attachments").size()>0);assertTrue(result.path("steps").get(1).path("attachments").size()>0);
        assertTrue(result.path("steps").get(1).path("steps").toString().contains("locator-healed"));
        assertEquals(4,result.path("attachments").findValuesAsText("type").stream().filter("image/png"::equals).count());
        assertTrue(Files.size(root.resolve("index.html"))>10000);assertEquals("Allure",Json.read(root.resolve("allure-generation.json"),Map.class).get("engine"));
        try(var directories=Files.list(root)) { assertEquals(List.of(older),directories.filter(p -> p.getFileName().toString().startsWith(".allure-render-")).toList()); }
        assertTrue(Files.isRegularFile(older.resolve("diagnostic.txt")));assertTrue(Files.isRegularFile(root.resolve("allure-results/attempt-result.json")));
        Files.writeString(root.resolve("index.html"),"tampered");assertThrows(Failure.class,() -> AllureReports.verify(root));
    }
    @Test void failedStepStaysFailedAndLaterPlannedStepsStaySkippedWithFailureScreenshot() throws Exception {
        var evidence=evidence();evidence.checkpoint("case-start");
        evidence.event(Map.of("kind","case-replay-plan","steps",List.of(Map.of("kind","click"),Map.of("kind","expectText"),Map.of("kind","expectVisible"))));
        evidence.event(Map.of("kind","case-step-started","step",1,"action","click"));evidence.event(Map.of("kind","case-step-passed","step",1,"action","click"));
        evidence.event(Map.of("kind","case-step-started","step",2,"action","expectText"));evidence.checkpoint("case-failure");
        var state=state("FAILED",evidence.artifacts());state.put("code","ASSERTION_FAILED");state.put("diagnostic","Expected outcome was not satisfied");
        WorkflowReportExporter.write(root,state,evidence.events());
        var result=Json.MAPPER.readTree(Files.readString(root.resolve("allure-results/attempt-result.json")));
        assertEquals("failed",result.path("status").asText());assertEquals("passed",result.path("steps").get(0).path("status").asText());
        assertEquals("failed",result.path("steps").get(1).path("status").asText());assertEquals("skipped",result.path("steps").get(2).path("status").asText());
        assertEquals("case-failure",result.path("steps").get(1).path("attachments").get(0).path("name").asText());WorkflowReportExporter.verify(root);
    }
    @Test void disabledExtraCapturesKeepMandatoryCheckpointsAndPlainJavaActionsGetSeparateScreenshots() throws Exception {
        var quiet=new EvidenceService(root,"run","quiet",() -> {try {return new ReportIntegrityTest.Engine();} catch(Exception e) {throw new RuntimeException(e);}},Deadline.after(Duration.ofSeconds(5)),new SecretRegistry(),false);
        quiet.checkpoint("case-start");quiet.event(Map.of("kind","case-step-passed","step",1));quiet.checkpoint("test-completion");assertEquals(2,quiet.artifacts().size());
        var defaults=evidence();defaults.event(Map.of("kind","locator-action","action","click"));defaults.event(Map.of("kind","locator-action","action","fill"));
        assertEquals(2,defaults.artifacts().size());assertEquals(2,AllureResults.steps(defaults.events(),defaults.artifacts(),"PASSED",null).size());
    }
    @Test void authenticationFailureProducesAnAllureBrokenResultWithoutInventedScreenshots() throws Exception {
        var state=state("FAILED",List.of());state.put("code","AUTH_CREDENTIAL_UNAVAILABLE");state.put("diagnostic","Missing configured credential reference");
        WorkflowReportExporter.write(root,state,List.of());
        var result=Json.MAPPER.readTree(Files.readString(root.resolve("allure-results/attempt-result.json")));
        assertEquals("broken",result.path("status").asText());assertEquals(0,result.path("attachments").findValuesAsText("type").stream().filter("image/png"::equals).count());
        WorkflowReportExporter.verify(root);
    }
    @Test void unsafeArchivePathsCannotEscapeTheAllureInstallationDirectory() throws Exception {
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)) {zip.putNextEntry(new ZipEntry("../escaped.txt"));zip.write(new byte[]{1});zip.closeEntry();}
        assertEquals("REPORT_GENERATION_FAILED",assertThrows(Failure.class,() -> AllureReports.extract(output.toByteArray(),root.resolve("cache"))).code());
        assertFalse(Files.exists(root.resolve("escaped.txt")));
    }
    @Test void temporaryCleanupRejectsOtherDirectoriesAndPreservesTheirContents() throws Exception {
        Path ordinary=root.resolve("allure-report");Files.createDirectories(ordinary);Files.writeString(ordinary.resolve("index.html"),"Published evidence");
        assertEquals("REPORT_GENERATION_FAILED",assertThrows(Failure.class,() -> AllureReports.removePublishedTemporary(root,ordinary)).code());
        assertTrue(Files.isRegularFile(ordinary.resolve("index.html")));
        Path outside=root.resolveSibling(".allure-render-"+UUID.randomUUID());
        assertEquals("REPORT_GENERATION_FAILED",assertThrows(Failure.class,() -> AllureReports.removePublishedTemporary(root,outside)).code());
    }
    @Test void secretsNeverReachAllureResultJsonOrRedactedEventAttachment() throws Exception {
        var secrets=new SecretRegistry();secrets.register("PRIVATE_ALLURE_SENTINEL");
        var evidence=new EvidenceService(root,"workflow","attempt",() -> {try {return new ReportIntegrityTest.Engine();} catch(Exception e) {throw new RuntimeException(e);}},Deadline.after(Duration.ofSeconds(30)),secrets);
        evidence.event(Map.of("kind","model-call-failed","code","MODEL_UNAVAILABLE","reason","PRIVATE_ALLURE_SENTINEL"));
        var state=state("FAILED",List.of());state.put("code","MODEL_UNAVAILABLE");state.put("diagnostic",secrets.redact("PRIVATE_ALLURE_SENTINEL"));
        WorkflowReportExporter.write(root,state,evidence.events());
        for(String file:List.of("case-report.json","allure-results/attempt-result.json","allure-results/attempt-events.json")) assertFalse(Files.readString(root.resolve(file)).contains("PRIVATE_ALLURE_SENTINEL"));
    }
    @Test void fakePassingWorkflowWithoutEvidenceOrWithAFailedQualityGateIsRejected() throws Exception {
        WorkflowReportExporter.write(root,state("PASSED",List.of()),List.of(),false);
        assertEquals("EVIDENCE_INCOMPLETE",assertThrows(Failure.class,() -> WorkflowReportExporter.verifyEvidence(root)).code());
        var evidence=evidence();evidence.checkpoint("case-start");evidence.checkpoint("test-completion");
        WorkflowReportExporter.write(root,state("PASSED",evidence.artifacts()),List.of(Map.of("kind","quality-gate","status","FAILED")),false);
        assertEquals("REPORT_RUN_MISMATCH",assertThrows(Failure.class,() -> WorkflowReportExporter.verifyEvidence(root)).code());
    }
}
