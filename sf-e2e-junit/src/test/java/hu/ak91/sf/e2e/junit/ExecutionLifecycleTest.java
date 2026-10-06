package hu.ak91.sf.e2e.junit;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.reporting.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Complete coordinator/extension execution with isolated engines, evidence and journals; no org access. */
class ExecutionLifecycleTest {
    @TempDir Path root;
    static final List<String> trace=new ArrayList<>();
    @BeforeEach void reset() { trace.clear(); }
    static class Engine implements UiEngine {
        boolean closed;private final byte[] png;
        Engine() throws Exception {
            var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(12,12,BufferedImage.TYPE_INT_RGB),"PNG",bytes);png=bytes.toByteArray();
        }
        public byte[] captureMaskedPng(Deadline deadline) { assertFalse(closed);return png.clone(); }
        public void close() { closed=true;trace.add("closed"); }
        public void open(String route,Deadline deadline) { assertFalse(closed); }
        public String route() { return "https://isolated.example/app"; }
        public Observation observe(String scope,Deadline deadline) { throw new UnsupportedOperationException(); }
        public Observation.Node resolve(SemanticTarget target,Observation observation) { throw new UnsupportedOperationException(); }
        public void perform(Action action,Observation observation,Deadline deadline) { throw new UnsupportedOperationException(); }
        public Map<String,String> readVisibleFields(Deadline deadline) { return Map.of(); }
        public void poll(Deadline deadline) {}
        public String capabilityVersion() { return "lifecycle-harness"; }
    }
    FrameworkConfig config() {
        var c=FrameworkConfig.defaults();
        return new FrameworkConfig(1,"lifecycle-harness","deterministic",new FrameworkConfig.Org("isolated","auto","https://isolated.example",List.of(),1,""),
            c.ui(),c.auth(),c.personas(),c.execution(),c.agent(),new FrameworkConfig.Cache("off",false,false,root.resolve("cache").toString(),"fail"),
            new FrameworkConfig.Retention("permanent",root.resolve("journals").toString()),c.evidence(),new FrameworkConfig.Report(root.resolve("runs").toString()),
            new FrameworkConfig.Locators(root.resolve("locators").toString(),"read-only",true,false,false,1,100,5,.90,.10));
    }
    @Test void defaultPageApiResolvesMappedControlsWithNoModelOrObservationCache() throws Exception {
        var config=config();var disk=new hu.ak91.sf.e2e.core.locator.LocatorStore(Path.of(config.locators().dir()),hu.ak91.sf.e2e.core.locator.LocatorStore.namespace(config,Persona.DEFAULT),"read-write");
        disk.putAll(List.of(new hu.ak91.sf.e2e.core.locator.LocatorEntry("Home","heading",hu.ak91.sf.e2e.core.locator.LocatorEntry.routeKey("https://isolated.example/app"),"lifecycle-harness",SemanticTarget.role("heading","Home","page"),Map.of(),List.of(),1,List.of())));
        var engine=new Engine() {
            int observations;
            public Observation observe(String scope,Deadline deadline) { return new Observation(++observations,java.time.Instant.now(),route(),List.of(new Observation.Node("n"+observations,SemanticTarget.role("heading","Home","page"),"Read "+observations,"",false,false)),false,"COMPLETE","SAFE",null); }
        };
        var info=new CaseInfo("TST_POM","TST_POM",Persona.DEFAULT,List.of(),List.of(),getClass().getName());
        try(var context=new SfTestContext(config,"pom-run",info,root,engine)) {
            context.initialize();var home=context.page("Home");assertSame(home,context.pages().page("Home"));
            assertEquals("Read 1",home.read("heading").text());assertEquals("Read 2",home.read("heading").text());
        }
        assertTrue(engine.closed);
    }
    RunReport run(Class<?>... classes) {
        var config=config();var plan=SelectionPlanner.plan(SelectionPlanner.discover(List.of(classes)),"deterministic",null,null);
        var report=RunCoordinator.run(config,plan,(session,info) -> {
            try { return new SfTestContext(config,session.runId,info,session.root,new Engine()); }
            catch(Exception error) { throw new AssertionError(error); }
        });
        ReportIntegrityVerifier.verify(root.resolve("runs").resolve(report.runId()),report,2);
        assertEquals(report.selection().size(),report.results().size());return report;
    }
    @ExtendWith(SfE2eExtension.class) @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    static class Successful {
        int counter;
        @BeforeAll void beforeAll() { trace.add("before-all"); }
        @AfterAll void afterAll() { trace.add("after-all");assertEquals(3,counter); }
        @AfterEach void teardown(SfTestContext sf) { assertFalse(((Engine)sf.engine()).closed);trace.add("teardown"); }
        @Test @SfCase(id="Z-SETUP",name="Setup") void setup(SfTestContext sf) { counter++;trace.add("setup"); }
        @Test @SfCase(id="A-CHILD",name="Child",dependencies={"Z-SETUP"}) void child(SfTestContext sf) { assertEquals(1,counter);counter++;trace.add("child"); }
        @Test @SfCase(id="B-SECOND-CHILD",name="Second child",dependencies={"Z-SETUP"}) void second(SfTestContext sf) { assertEquals(2,counter);counter++;trace.add("second-child"); }
    }
    @Test void successfulDependencyChainPreservesClassInstanceAndKeepsEngineOpenThroughTeardown() {
        var report=run(Successful.class);assertEquals(0,report.exitCode());assertEquals(3,report.counts().get("PASSED"));
        assertEquals(List.of("before-all","setup","teardown","closed","child","teardown","closed","second-child","teardown","closed","after-all"),trace);
    }
    @ExtendWith(SfE2eExtension.class)
    static class TeardownFailure {
        @AfterEach void teardown(SfTestContext sf) { assertFalse(((Engine)sf.engine()).closed);trace.add("failing-teardown");throw new AssertionError("Teardown failure"); }
        @Test @SfCase(id="TEARDOWN-SETUP",name="Setup") void setup(SfTestContext sf) { trace.add("body-passed"); }
        @Test @SfCase(id="TEARDOWN-CHILD",name="Child",dependencies={"TEARDOWN-SETUP"}) void child(SfTestContext sf) { trace.add("must-not-run"); }
    }
    @Test void userTeardownFailureCannotPassOrReleaseDependentCase() {
        var report=run(TeardownFailure.class);
        assertEquals(List.of("FAILED","NOT_RUN_DEPENDENCY"),report.results().stream().map(AttemptResult::status).toList());
        assertFalse(trace.contains("must-not-run"));assertEquals("UI_ASSERTION_FAILED",report.results().getFirst().errorCode());
        assertTrue(trace.indexOf("failing-teardown")<trace.indexOf("closed"));
    }
    static class LateCallback implements AfterEachCallback {
        public void afterEach(ExtensionContext context) { throw new AssertionError("Late callback failure"); }
    }
    @ExtendWith({LateCallback.class,SfE2eExtension.class})
    static class LateFailure {
        @Test @SfCase(id="LATE-SETUP",name="Setup") void setup(SfTestContext sf) { trace.add("late-body"); }
        @Test @SfCase(id="LATE-CHILD",name="Child",dependencies={"LATE-SETUP"}) void child(SfTestContext sf) { trace.add("must-not-run"); }
    }
    @Test void callbackFailureAfterFrameworkFinalizationIsReconciledBeforeNextMethod() {
        var report=run(LateFailure.class);
        assertEquals("FAILED",report.results().getFirst().status());assertEquals("Late callback failure",report.results().getFirst().errorMessage());
        assertEquals("NOT_RUN_DEPENDENCY",report.results().getLast().status());assertFalse(trace.contains("must-not-run"));
    }
    static class LateAbort implements AfterEachCallback {
        public void afterEach(ExtensionContext context) { throw new org.opentest4j.TestAbortedException("Late callback abort"); }
    }
    @ExtendWith({LateAbort.class,SfE2eExtension.class})
    static class AbortedCase {
        @Test @SfCase(id="ABORTED",name="Aborted") void sample(SfTestContext sf) {}
    }
    @Test void lateAbortedJUnitOutcomeCannotRemainPassedInTheReport() {
        var report=run(AbortedCase.class);assertEquals("SKIPPED",report.results().getFirst().status());
        assertEquals("TEST_ABORTED",report.results().getFirst().errorCode());assertEquals(2,report.exitCode());
    }
    @ExtendWith(SfE2eExtension.class)
    static class ClassFailure {
        @AfterAll static void fail() { throw new AssertionError("Class teardown failure"); }
        @Test @SfCase(id="CLASS-SETUP",name="Setup") void setup(SfTestContext sf) { trace.add("class-setup"); }
    }
    @ExtendWith(SfE2eExtension.class)
    static class ClassChild {
        @BeforeAll static void beforeAll() { trace.add("child-class-started"); }
        @Test @SfCase(id="CLASS-CHILD",name="Child",dependencies={"CLASS-SETUP"}) void child(SfTestContext sf) { trace.add("must-not-run"); }
    }
    @Test void classTeardownFailureChangesReportAndBlocksDependentClassBeforeItsFixtures() {
        var report=run(ClassChild.class,ClassFailure.class);
        assertEquals("FAILED",report.results().getFirst().status());assertEquals("Class teardown failure",report.results().getFirst().errorMessage());
        assertEquals("NOT_RUN_DEPENDENCY",report.results().getLast().status());assertFalse(trace.contains("child-class-started"));
    }
    @ExtendWith(SfE2eExtension.class)
    static class BeforeAllFailure {
        @BeforeAll static void fail() { throw new AssertionError("Class setup failure"); }
        @Test @SfCase(id="CLASS-SETUP",name="Setup") void setup(SfTestContext sf) { trace.add("must-not-run"); }
    }
    @Test void classSetupFailureProducesBlockedResultAndBlocksDependentClass() {
        var report=run(BeforeAllFailure.class,ClassChild.class);
        assertEquals(List.of("BLOCKED","NOT_RUN_DEPENDENCY"),report.results().stream().map(AttemptResult::status).toList());
        assertEquals(2,report.exitCode());assertTrue(trace.isEmpty());
    }
    @ExtendWith(SfE2eExtension.class)
    static class DisabledCase {
        @Disabled("Synthetic disabled case") @Test @SfCase(id="DISABLED",name="Disabled") void sample(SfTestContext sf) { trace.add("must-not-run"); }
    }
    @Test void disabledCaseIsReportedAsSkippedWithNoAuthenticationOrBody() {
        var report=run(DisabledCase.class);assertEquals("SKIPPED",report.results().getFirst().status());assertEquals(2,report.exitCode());assertTrue(trace.isEmpty());
    }
    @ExtendWith(SfE2eExtension.class)
    static class DirectCases {
        @BeforeAll static void beforeAll() { trace.add("must-not-run"); }
        @Test @SfCase(id="DIRECT-SETUP",name="Setup") void setup(SfTestContext sf) { trace.add("must-not-run"); }
        @Test @SfCase(id="DIRECT-CHILD",name="Child",dependencies={"DIRECT-SETUP"}) void child(SfTestContext sf) { trace.add("must-not-run"); }
    }
    @Test void directJUnitLaunchFailsBeforeUserFixturesInsteadOfBypassingDependencies() {
        var summary=new SummaryGeneratingListener();
        LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(DirectCases.class)).build(),summary);
        assertTrue(trace.isEmpty());assertFalse(summary.getSummary().getFailures().isEmpty());
        assertTrue(summary.getSummary().getFailures().stream().anyMatch(f -> f.getException() instanceof Failure failure &&
            failure.code().equals("CONFIG_INVALID") && failure.getMessage().contains("RunCoordinator")));
        assertNull(RunSession.CURRENT.get());
    }
}
