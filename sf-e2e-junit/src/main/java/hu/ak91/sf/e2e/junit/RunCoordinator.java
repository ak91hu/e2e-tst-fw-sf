package hu.ak91.sf.e2e.junit;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.reporting.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.*;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.engine.TestExecutionResult;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
public final class RunCoordinator {
    private RunCoordinator() {}
    public static RunReport run(FrameworkConfig config,List<SelectionPlanner.Discovered> plan) { return run(config,plan,(Path)null); }
    public static RunReport run(FrameworkConfig config,List<SelectionPlanner.Discovered> plan,Path preparedRoot) {
        try { return run(config,plan,(session,info) -> {hu.ak91.sf.e2e.engine.PlaywrightEngine.ensureWorkerRuntime(session.config);return new SfTestContext(session.config,session.runId,info,session.root);},preparedRoot); }
        finally { hu.ak91.sf.e2e.engine.PlaywrightEngine.closeWorkerRuntime(); }
    }
    static RunReport run(FrameworkConfig config,List<SelectionPlanner.Discovered> plan,RunSession.ContextFactory factory) { return run(config,plan,factory,null); }
    private static RunReport run(FrameworkConfig config,List<SelectionPlanner.Discovered> plan,RunSession.ContextFactory factory,Path preparedRoot) {
        Path root=preparedRoot==null?ReportDirectories.create(Path.of(config.report().root()),java.time.Instant.now()):preparedRoot;
        var preflight=new PreflightReport(root,"JUnit run");
        preflight.design("Selected Java cases (method bodies require execution)",plan.stream().map(SelectionPlanner.Discovered::info).toList());
        try {
            preflight.check("Configuration, selection and dependency execution order",() -> validatePlan(config,plan));
            preflight.ready();
        } catch(RuntimeException error) {
            try {preflight.blocked(error);HtmlSummary.blocked(root,"JUnit run",error);}catch(RuntimeException reporting) {error.addSuppressed(reporting);}
            throw error;
        }
        var ordered=SelectionPlanner.executionOrder(plan);
        Map<Class<?>,List<SelectionPlanner.Discovered>> groups=new LinkedHashMap<>();
        ordered.forEach(c -> groups.computeIfAbsent(c.method().getDeclaringClass(),k -> new ArrayList<>()).add(c));
        var session=new RunSession(config,ordered.stream().map(SelectionPlanner.Discovered::info).toList(),factory,root);
        Path lockPath=SafePaths.inside(Path.of(System.getProperty("user.home"),".sf-e2e","locks"),Json.hash(config.org().baseUrl())+".lock");
        try {
            Files.createDirectories(lockPath.getParent());
            try(FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                FileLock lock=channel.tryLock()) {
                Failure.require(lock!=null,"ENVIRONMENT_UNAVAILABLE","Another local run holds the org lock");
                RunSession.CURRENT.set(session);
                try {
                    var launcher=LauncherFactory.create();
                    for(var group:groups.values()) {
                        if(Thread.currentThread().isInterrupted()) {
                            group.forEach(c -> session.notRun(c.info(),"CANCELLED","CANCELLED","Run interrupted"));continue;
                        }
                        // Skip classes whose every case already has a failed dependency, before user @BeforeAll.
                        if(group.stream().allMatch(c -> c.info().dependencies().stream().anyMatch(id -> session.has(id) && !session.passed(id)))) {
                            group.forEach(c -> session.notRun(c.info(),"NOT_RUN_DEPENDENCY","SETUP_DEPENDENCY_FAILED","Required setup did not pass"));continue;
                        }
                        var summary=new SummaryGeneratingListener();
                        var selectors=group.stream().map(c -> selectMethod(c.method().getDeclaringClass(),c.method().getName(),c.method().getParameterTypes())).toList();
                        var request=LauncherDiscoveryRequestBuilder.request().selectors(selectors)
                            .configurationParameter("junit.jupiter.execution.parallel.enabled","false")
                            .configurationParameter("junit.jupiter.testmethod.order.default",DependencyMethodOrderer.class.getName()).build();
                        var listener=new TestExecutionListener() {
                            public void executionSkipped(TestIdentifier id,String reason) {
                                if(id.isTest()) find(group,id).ifPresent(c -> skipped(session,c));
                                else group.forEach(c -> skipped(session,c));
                            }
                            public void executionFinished(TestIdentifier id,TestExecutionResult result) {
                                if(id.isTest() && result.getStatus()!=TestExecutionResult.Status.SUCCESSFUL)
                                    find(group,id).ifPresent(c -> session.junitFailure(c.info(),result.getThrowable().orElseGet(() ->
                                        result.getStatus()==TestExecutionResult.Status.ABORTED?new org.opentest4j.TestAbortedException("JUnit test aborted"):
                                            new Failure("ENGINE_FAILURE","JUnit test failed"))));
                            }
                        };
                        launcher.execute(request,listener,summary);
                        // @AfterAll and other container errors arrive after individual case finalization.
                        for(var failure:summary.getSummary().getFailures()) {
                            if(failure.getTestIdentifier().isTest()) find(group,failure.getTestIdentifier()).ifPresent(c -> session.junitFailure(c.info(),failure.getException()));
                            else group.forEach(c -> session.junitFailure(c.info(),failure.getException()));
                        }
                        for(var selected:group) if(!session.has(selected.info().id()))
                            session.notRun(selected.info(),"BLOCKED","ENGINE_FAILURE","JUnit did not produce the selected case result");
                    }
                } finally { RunSession.CURRENT.remove(); }
            }
        } catch(Exception e) {
            var failure=e instanceof Failure f?f:new Failure("ENVIRONMENT_UNAVAILABLE","Could not acquire local org lock or launch JUnit");
            for(var selected:plan) if(!session.has(selected.info().id())) session.notRun(selected.info(),"BLOCKED",failure.code(),"Run interrupted before a final case result");
            try { session.complete(); } catch(RuntimeException reporting) { failure.addSuppressed(reporting); }
            throw failure;
        }
        return session.complete();
    }
    private static void validatePlan(FrameworkConfig config,List<SelectionPlanner.Discovered> plan) {
        config.validate();
        Failure.require(config.execution().workers()==1,"CONFIG_INVALID","Parallel workers belong to run-suite; dependency-aware Java case execution uses one owner thread");
        Failure.require(!plan.isEmpty(),"NO_TESTS","No user test cases selected. Supply test classes or run UI mapping first.");
        Failure.require(!config.profile().equals("checks"),"CONFIG_INVALID","Live runner cannot use checks profile");
        Failure.require(!config.org().baseUrl().isBlank(),"CONFIG_INVALID","SF_BASE_URL is required");
        boolean ai=plan.stream().anyMatch(c -> c.info().tags().contains("ai"));
        Failure.require(!Set.of("deterministic","regression").contains(config.profile()) || !ai,"CONFIG_INVALID","No-model execution cannot select AI cases");
        if(ai) Failure.require(!config.agent().model().isBlank() && System.getenv(config.agent().apiKeyRef())!=null,"CONFIG_INVALID","Selected AI cases require a model ID and API-key reference");
        SelectionPlanner.executionOrder(plan);
    }
    private static Optional<SelectionPlanner.Discovered> find(List<SelectionPlanner.Discovered> group,TestIdentifier id) {
        return id.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast)
            .flatMap(source -> group.stream().filter(c -> c.info().source().equals(source.getClassName()+"#"+source.getMethodName())).findFirst());
    }
    private static void skipped(RunSession session,SelectionPlanner.Discovered selected) {
        if(!session.has(selected.info().id())) session.notRun(selected.info(),"SKIPPED","TEST_DISABLED","JUnit disabled the selected case");
    }
}
