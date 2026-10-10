package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TestDesignReportTest {
    @TempDir Path root;
    private Map<String,Object> state(String status) {
        var state=new LinkedHashMap<String,Object>();
        state.put("schemaVersion",1);state.put("runId","fixture");state.put("attemptId","attempt");state.put("caseId","TST_design");
        state.put("mode","learning");state.put("status",status);state.put("startedAt",Instant.now());state.put("completedAt",Instant.now());
        return state;
    }
    private CaseSpec spec() {
        return new CaseSpec("design","/app",List.of(
            new CaseSpec.Step("fill","Input <script>alert('unsafe')</script>",null,null,"input",null),
            new CaseSpec.Step("expectValue","Input",null,null,"expected",null),
            new CaseSpec.Step("expectVisible","Result",null,null,null,null)));
    }
    private List<Map<String,Object>> events(boolean complete) {
        var events=new ArrayList<Map<String,Object>>();
        events.add(Map.of("kind","case-instructions-interpreted","sourceSteps",List.of(1,2,3),"sourceInstructionCount",3));
        events.add(Map.of("kind","case-plan-ready","plan",spec(),"parameterNames",Set.of("input","expected")));
        for(int i=1;i<=(complete?3:2);i++) {
            events.add(Map.of("kind","case-step-started","step",i));
            if(complete || i==1) events.add(Map.of("kind","case-step-learned","step",i,"implementation",
                new CaseRecipe.Step(spec().steps().get(i-1).kind(),"page.fixture","step."+i,null,spec().steps().get(i-1).valueRef(),null)));
        }
        return events;
    }
    @Test void failedLearningRetainsEntireDesignOrderedOutcomesEscapedTargetsAndImplementationReferences() throws Exception {
        var state=state("FAILED");state.put("code","ASSERTION_FAILED");state.put("parameters",Map.of("input","PRIVATE_RUNTIME_SENTINEL"));
        WorkflowReportExporter.write(root,state,events(false),false);
        String html=Files.readString(root.resolve("test-design.html"));
        assertTrue(html.contains("&lt;script&gt;"));assertFalse(html.contains("<script>"));assertFalse(html.contains("PRIVATE_RUNTIME_SENTINEL"));
        assertTrue(html.contains("${expected}"));assertTrue(html.contains("page.fixture"));assertTrue(html.contains("step.1"));
        assertTrue(html.contains("STARTED — NOT COMPLETED"));assertTrue(html.contains("NOT EXECUTED"));
        assertTrue(html.indexOf("<td>fill</td>")<html.indexOf("<td>expectValue</td>"));
        assertTrue(html.indexOf("<td>expectValue</td>")<html.indexOf("<td>expectVisible</td>"));
        assertTrue(html.contains("<td>3 / 3</td>"));assertTrue(html.contains("ASSERTION_FAILED"));
        var result=Json.MAPPER.readTree(Files.readString(root.resolve("allure-results/attempt-result.json")));
        assertTrue(result.path("attachments").toString().contains("Implemented test design"));
        WorkflowReportExporter.verifyEvidence(root);
    }
    @Test void completedStepsDoNotTurnFailedFinalizationIntoPassedDesign() throws Exception {
        var state=state("FAILED");state.put("code","EVIDENCE_INCOMPLETE");
        WorkflowReportExporter.write(root,state,events(true),false);
        String html=Files.readString(root.resolve("test-design.html"));
        assertEquals(3,html.split("<td>COMPLETED</td>",-1).length-1);assertTrue(html.contains("<strong>FAILED</strong>"));
        assertFalse(html.contains("NOT EXECUTED</td>"));WorkflowReportExporter.verifyEvidence(root);
    }
    @Test void changedOrMissingDesignAndAllureCopyFailIntegrityVerification() throws Exception {
        for(Path altered:List.of(root.resolve("test-design.html"),root.resolve("allure-results/attempt-test-design.html"))) {
            WorkflowReportExporter.write(root,state("FAILED"),events(false),false);Files.writeString(altered,"tampered");
            assertEquals("REPORT_RUN_MISMATCH",assertThrows(Failure.class,() -> WorkflowReportExporter.verifyEvidence(root)).code());
        }
        WorkflowReportExporter.write(root,state("FAILED"),events(false),false);Files.delete(root.resolve("test-design.html"));
        assertEquals("REPORT_RUN_MISMATCH",assertThrows(Failure.class,() -> WorkflowReportExporter.verifyEvidence(root)).code());
    }
    @Test void redactedEventsFeedTheDesignAndLegacyReportsWithoutPlansRemainCompatible() throws Exception {
        var secrets=new SecretRegistry();secrets.register("PRIVATE_LABEL_SENTINEL");
        var evidence=new EvidenceService(root,"fixture","attempt",() -> null,Deadline.after(java.time.Duration.ofSeconds(5)),secrets,false);
        evidence.event(Map.of("kind","case-plan-ready","plan",new CaseSpec("design","/app",List.of(new CaseSpec.Step("expectVisible","PRIVATE_LABEL_SENTINEL",null,null,null,null)))));
        WorkflowReportExporter.write(root,state("FAILED"),evidence.events(),false);
        assertFalse(Files.readString(root.resolve("test-design.html")).contains("PRIVATE_LABEL_SENTINEL"));
        assertTrue(Files.readString(root.resolve("test-design.html")).contains("[REDACTED]"));
        Path legacy=root.resolve("legacy");WorkflowReportExporter.write(legacy,state("FAILED"),List.of(),false);
        assertFalse(Files.exists(legacy.resolve("test-design.html")));WorkflowReportExporter.verifyEvidence(legacy);
    }
    @Test void malformedDesignReceiptCannotBypassIntegrityVerification() {
        for(Object malformed:Arrays.asList(null,"invalid",List.of())) {
            WorkflowReportExporter.write(root,state("FAILED"),events(false),false);
            @SuppressWarnings("unchecked") var report=(Map<String,Object>)Json.read(root.resolve("case-report.json"),Map.class);
            report.put("testDesign",malformed);Json.write(root.resolve("case-report.json"),report);
            assertEquals("REPORT_RUN_MISMATCH",assertThrows(Failure.class,() -> WorkflowReportExporter.verifyEvidence(root)).code());
        }
    }
    @Test void matchingForeignAttachmentCannotReplaceTheCurrentAttemptDesign() throws Exception {
        WorkflowReportExporter.write(root,state("FAILED"),events(false),false);
        Files.copy(root.resolve("allure-results/attempt-test-design.html"),root.resolve("allure-results/foreign-test-design.html"));
        @SuppressWarnings("unchecked") var report=(Map<String,Object>)Json.read(root.resolve("case-report.json"),Map.class);
        @SuppressWarnings("unchecked") var receipt=new LinkedHashMap<>((Map<String,Object>)report.get("testDesign"));
        receipt.put("source","foreign-test-design.html");report.put("testDesign",receipt);Json.write(root.resolve("case-report.json"),report);
        assertEquals("REPORT_RUN_MISMATCH",assertThrows(Failure.class,() -> WorkflowReportExporter.verifyEvidence(root)).code());
    }
}
