package hu.ak91.sf.e2e.junit;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.quality.BrowserQuality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserQualityLifecycleTest {
    @TempDir Path root;
    private FrameworkConfig config() {
        var c=FrameworkConfig.defaults();return new FrameworkConfig(1,"quality-lifecycle","deterministic",new FrameworkConfig.Org("target","auto","https://application.example",List.of(),1,""),c.ui(),c.auth(),c.personas(),c.execution(),c.agent(),c.cache(),new FrameworkConfig.Retention("permanent",root.resolve("journals").toString()),c.evidence(),new FrameworkConfig.Report(root.resolve("reports").toString()),c.locators());
    }
    private SfTestContext context(ExecutionLifecycleTest.Engine engine) {
        return new SfTestContext(config(),"run",new CaseInfo("quality","Quality",Persona.DEFAULT,List.of(),List.of(),"internal"),root,engine);
    }
    @Test void successfulUserBodyFailsOnBrowserDefectAndPreservesFailureEvidence() throws Exception {
        var c=FrameworkConfig.defaults();var config=new FrameworkConfig(1,"quality-lifecycle","deterministic",new FrameworkConfig.Org("target","auto","https://application.example",List.of(),1,""),c.ui(),c.auth(),c.personas(),c.execution(),c.agent(),c.cache(),new FrameworkConfig.Retention("permanent",root.resolve("journals").toString()),c.evidence(),new FrameworkConfig.Report(root.resolve("reports").toString()),c.locators());
        var info=new CaseInfo("quality","Quality",Persona.DEFAULT,List.of(),List.of(),"internal");
        var engine=new ExecutionLifecycleTest.Engine() { public List<BrowserQuality.Issue> browserIssues() {return List.of(new BrowserQuality.Issue("PAGE_ERROR",Instant.now(),"https://application.example","/app","Unhandled exception",0));} };
        var context=new SfTestContext(config,"run",info,root,engine);context.initialize();var result=context.finish(null);
        assertEquals("FAILED",result.status());assertEquals("APPLICATION_DEFECT",result.errorCode());assertTrue(engine.closed);
        assertTrue(result.artifacts().stream().anyMatch(a -> a.stage().equals("failure-original-state")));assertTrue(result.events().stream().anyMatch(e -> "browser-issue".equals(e.get("kind"))));
    }
    private static class CaptureDefectEngine extends ExecutionLifecycleTest.Engine {
        int captures;
        CaptureDefectEngine() throws Exception {}
        @Override public byte[] captureMaskedPng(Deadline deadline) { captures++;return super.captureMaskedPng(deadline); }
        @Override public List<BrowserQuality.Issue> browserIssues() {
            return captures<2?List.of():List.of(new BrowserQuality.Issue("PAGE_ERROR",Instant.now(),"https://application.example","/app","Error delivered during final capture",0));
        }
    }
    @Test void defectDeliveredDuringCompletionCaptureCannotProduceAPassingVerdict() throws Exception {
        var engine=new CaptureDefectEngine();var context=context(engine);context.initialize();var result=context.finish(null);
        assertEquals("FAILED",result.status());assertEquals("FAILED",result.businessVerdict());assertEquals("APPLICATION_DEFECT",result.errorCode());
        assertTrue(engine.closed);assertTrue(result.artifacts().stream().anyMatch(a -> a.stage().equals("test-completion")));
        assertTrue(result.artifacts().stream().anyMatch(a -> a.stage().equals("failure-original-state")));
        assertEquals(1,result.events().stream().filter(e -> "browser-issue".equals(e.get("kind"))).count());
    }
    @Test void defectDeliveredDuringFailureCaptureIsReportedWithoutReplacingTheOriginalAssertion() throws Exception {
        var engine=new CaptureDefectEngine();var context=context(engine);context.initialize();var result=context.finish(new AssertionError("Business outcome was wrong"));
        assertEquals("FAILED",result.status());assertEquals("UI_ASSERTION_FAILED",result.errorCode());assertEquals("Business outcome was wrong",result.errorMessage());
        assertTrue(result.secondaryErrors().stream().anyMatch(s -> s.contains("APPLICATION_DEFECT")));
        assertEquals(1,result.events().stream().filter(e -> "browser-issue".equals(e.get("kind"))).count());assertTrue(engine.closed);
    }
}
