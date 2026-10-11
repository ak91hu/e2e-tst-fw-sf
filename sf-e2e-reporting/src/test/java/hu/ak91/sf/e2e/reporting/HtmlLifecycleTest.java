package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HtmlLifecycleTest {
    @TempDir Path root;
    @Test void preflightRecordsBlockersEscapesTargetsAndNeverPublishesRuntimeValuesOrRawErrors() throws Exception {
        var report=new PreflightReport(root,"fixture");
        report.design("TST_<unsafe>",List.of(new CaseSpec.Step("expectValue","Private value SECRET_SENTINEL <script>",null,null,"expected",null)),Map.of("expected","SECRET_SENTINEL"));
        report.check("First design",() -> {});
        var blocker=assertThrows(Failure.class,() -> report.check("Missing input",() -> {throw new Failure("CASE_PARAMETER_REQUIRED","SECRET_SENTINEL");}));report.blocked(blocker);
        String html=Files.readString(root.resolve("preflight.html"));
        assertTrue(html.contains("BLOCKED"));assertTrue(html.contains("CASE_PARAMETER_REQUIRED"));
        assertTrue(html.contains("&lt;script&gt;"));assertFalse(html.contains("<script>"));assertFalse(html.contains("SECRET_SENTINEL"));
        assertFalse(Files.readString(root.resolve("preflight.json")).contains("SECRET_SENTINEL"));PreflightReport.verify(root);
        Files.writeString(root.resolve("preflight.html"),"tampered");assertThrows(Failure.class,() -> PreflightReport.verify(root));
    }
    @Test void initialDesignExistsWhileRunningAndFinalHtmlRetainsUncertainAndUnexecutedSteps() throws Exception {
        var state=new LinkedHashMap<String,Object>();state.put("schemaVersion",1);state.put("caseId","TST_fixture");state.put("attemptId","fixture");
        state.put("runId","run");state.put("mode","learning");state.put("status","RUNNING");state.put("startedAt",Instant.now());
        var spec=new CaseSpec("TST_fixture","/app",List.of(new CaseSpec.Step("click","Save",null,null,null,null),new CaseSpec.Step("expectVisible","Result",null,null,null,null)));
        var events=new ArrayList<Map<String,Object>>();events.add(Map.of("kind","case-plan-ready","plan",spec));
        WorkflowReportExporter.writeDesign(root,state,events);
        assertTrue(Files.readString(root.resolve("test-design.html")).contains("RUNNING"));assertFalse(Files.exists(root.resolve("case-report.json")));
        events.add(Map.of("kind","case-step-started","step",1,"action","click"));
        state.put("status","FAILED");state.put("code","ACTION_COMMIT_UNCERTAIN");state.put("completedAt",Instant.now());
        WorkflowReportExporter.write(root,state,events,false);
        String html=Files.readString(root.resolve("results.html"));assertTrue(html.contains("ACTION_COMMIT_UNCERTAIN"));
        assertTrue(html.contains("dispatch outcome may be uncertain"));assertTrue(html.contains("NOT EXECUTED"));
        WorkflowReportExporter.verifyEvidence(root);Files.delete(root.resolve("results.html"));assertThrows(Failure.class,() -> WorkflowReportExporter.verifyEvidence(root));
    }
    @Test void missingAllureRuntimeLeavesDetailedBlockedHtmlAndOriginalFailureCode() throws Exception {
        var state=new LinkedHashMap<String,Object>();state.put("schemaVersion",1);state.put("caseId","TST_fixture");state.put("attemptId","fixture");
        state.put("runId","run");state.put("mode","replay");state.put("status","FAILED");state.put("code","ASSERTION_FAILED");
        state.put("startedAt",Instant.now());state.put("completedAt",Instant.now());
        String previous=System.getProperty("sf.e2e.nodeExecutable");
        try {
            System.setProperty("sf.e2e.nodeExecutable",root.resolve("missing-node").toString());
            assertEquals("REPORT_RUNTIME_UNAVAILABLE",assertThrows(Failure.class,() -> WorkflowReportExporter.write(root,state,List.of())).code());
            String html=Files.readString(root.resolve("results.html"));assertTrue(html.contains("<strong>BLOCKED</strong>"));
            assertTrue(html.contains("ASSERTION_FAILED"));assertTrue(html.contains("REPORT_RUNTIME_UNAVAILABLE"));
            assertEquals(html,Files.readString(root.resolve("index.html")));WorkflowReportExporter.verifyEvidence(root);
        } finally { if(previous==null) System.clearProperty("sf.e2e.nodeExecutable");else System.setProperty("sf.e2e.nodeExecutable",previous); }
    }
}
