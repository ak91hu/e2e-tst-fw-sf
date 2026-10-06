package hu.ak91.sf.e2e.junit;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.reporting.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleDependencyTest {
    @TempDir Path root;
    @ExtendWith(SfE2eExtension.class)
    static class Cases {
        @Test @SfCase(id="SF-AUTH",name="Offline authentication failure",dependencies={})
        public void auth(SfTestContext sf) { throw new AssertionError("Missing credentials must stop before test invocation"); }
        @Test @SfCase(id="SF-OPP-001",name="Blocked dependent",dependencies={"SF-AUTH"})
        public void business(SfTestContext sf) { throw new AssertionError("Failed setup must prevent business invocation"); }
    }
    @Test void failedSetupProducesOneBlockedAndOneNotRunUnderSameRunId() {
        var c=FrameworkConfig.defaults();
        var config=new FrameworkConfig(1,c.projectId(),"regression",new FrameworkConfig.Org("offline-org","developer-edition","https://offline.develop.my.salesforce.com",List.of(),1,""),
            c.ui(),new FrameworkConfig.Auth("MISSING_TEST_CLIENT","MISSING_TEST_KEY","MISSING_TEST_KEY_FILE",c.auth().audience(),"MISSING_TEST_ADMIN"),c.personas(),c.execution(),c.agent(),c.cache(),
            new FrameworkConfig.Retention("permanent",root.resolve("permanent").toString()),c.evidence(),new FrameworkConfig.Report(root.resolve("runs").toString()));
        var plan=SelectionPlanner.plan(SelectionPlanner.discover(List.of(Cases.class)),"regression",null,null);
        var report=RunCoordinator.run(config,plan);
        assertEquals(2,report.results().size());assertEquals("BLOCKED",report.results().getFirst().status());
        assertEquals("NOT_RUN_DEPENDENCY",report.results().getLast().status());assertEquals(2,report.exitCode());
        Path runRoot=root.resolve("runs").resolve(report.runId());
        ReportIntegrityVerifier.verify(runRoot,report,2);
        assertTrue(Files.exists(runRoot.resolve("junit.xml")));assertTrue(Files.exists(runRoot.resolve("completion.json")));
        assertEquals(report.runId(),report.results().getFirst().runId());
    }
}
