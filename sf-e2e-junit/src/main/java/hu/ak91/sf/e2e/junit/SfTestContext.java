package hu.ak91.sf.e2e.junit;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.quality.BrowserQuality;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.page.*;
import hu.ak91.sf.e2e.engine.*;
import hu.ak91.sf.e2e.agent.*;
import hu.ak91.sf.e2e.salesforce.auth.*;
import hu.ak91.sf.e2e.salesforce.fixture.*;
import hu.ak91.sf.e2e.reporting.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

public final class SfTestContext implements AutoCloseable {
    private final FrameworkConfig config;
    private final CaseInfo info;
    private final String runId,attemptId=UUID.randomUUID().toString();
    private final Instant started=Instant.now();
    private final Deadline deadline;
    private final SecretRegistry secrets=new SecretRegistry();
    private final RecordRegistry records;
    private final EvidenceService evidence;
    private final Map<Persona,UiEngine> sessions=new HashMap<>();
    private final Map<Persona,SfAgent> agents=new HashMap<>();
    private final Map<Persona,HealingLocators> locators=new HashMap<>();
    private final Map<Persona,PageObjects> pages=new HashMap<>();
    private Persona persona;
    private ModelClient model;
    private boolean finished;
    private AttemptResult result;
    public SfTestContext(FrameworkConfig config,String runId,CaseInfo info,Path runRoot) {
        this.config=config;this.runId=runId;this.info=info;persona=info.persona();
        deadline=Deadline.after(Duration.ofSeconds(config.execution().testTimeoutSeconds()));
        records=new RecordRegistry(config,runId,attemptId,info.id());
        evidence=new EvidenceService(runRoot,runId,attemptId,() -> sessions.get(persona),deadline,secrets,config.evidence().screenshotsAfterSteps());
    }
    /** Package-private injection for credential-free lifecycle verification. */
    SfTestContext(FrameworkConfig config,String runId,CaseInfo info,Path runRoot,UiEngine initialEngine) {
        this(config,runId,info,runRoot);sessions.put(persona,Objects.requireNonNull(initialEngine));
    }
    public void initialize() {
        if(sessions.containsKey(persona)) { sessions.get(persona).startQualityMonitoring();evidence.checkpoint("authenticated-list"); }
        else switchPersona(info.persona());
    }
    public void switchPersona(Persona target) {
        deadline.check();
        config.persona(target);
        persona=target;
        boolean fresh=!sessions.containsKey(target);
        if(fresh) {
            var authenticator=new JwtAuthenticator(config,secrets);authenticator.preflight(Set.of(target));
            var engine=new PlaywrightEngine(config,secrets,deadline,target);sessions.put(target,engine);
            engine.operationEvents(evidence::event);
            if(!config.ui().fileInputRoot().isBlank()) engine.configureFiles(Path.of(config.ui().fileInputRoot()),evidence.attemptRoot().resolve("private-downloads"),evidence::event);
            engine.bootstrap(authenticator.frontdoor(target,deadline),deadline);
            engine.startQualityMonitoring();
            if(config.evidence().privateTraceOnFailure()) engine.startPrivateTrace(evidence.attemptRoot().resolve("private-trace"),attemptId);
        }
        evidence.checkpoint(fresh?"authenticated-list":"persona-switch");
        evidence.event(Map.of("kind","persona-authenticated","persona",target));
    }
    public void switchPersona(String alias) { switchPersona(Persona.valueOf(alias)); }
    public UiEngine engine() { return sessions.get(persona); }
    public RecordRegistry records() { return records; }
    public EvidenceService evidence() { return evidence; }
    /** Explicit, persona-authorized independent backend oracle. API version/bounds are supplied by the installation. */
    public hu.ak91.sf.e2e.salesforce.api.SalesforceApi api(hu.ak91.sf.e2e.salesforce.api.SalesforceApi.Policy policy) {
        String token=new JwtAuthenticator(config,secrets).apiAccess(persona,deadline);
        return new hu.ak91.sf.e2e.salesforce.api.SalesforceApi(config.org().baseUrl(),token,policy,secrets,evidence);
    }
    public hu.ak91.sf.e2e.salesforce.api.OwnedFixtures fixtures(hu.ak91.sf.e2e.salesforce.api.SalesforceApi api) {
        return new hu.ak91.sf.e2e.salesforce.api.OwnedFixtures(api,records);
    }
    public void accessibility(List<String> rules,Set<String> impacts,List<hu.ak91.sf.e2e.core.quality.QualityModules.Waiver> waivers) {
        qualityModule("accessibility",() -> { Failure.require(engine() instanceof PlaywrightEngine,"AUTOMATION_UNSUPPORTED","Accessibility needs a web engine");var result=((PlaywrightEngine)engine()).accessibilityAudit(rules,deadline);evidence.event(Map.of("kind","accessibility-audit","result",result));hu.ak91.sf.e2e.core.quality.QualityModules.accessibility(result,impacts,waivers,Clock.systemUTC()); });
    }
    public void visual(Path baseline,hu.ak91.sf.e2e.core.quality.QualityModules.Environment environment,int tolerance,double maximumRatio) {
        qualityModule("visual",() -> hu.ak91.sf.e2e.core.quality.QualityModules.visual(baseline,engine().captureMaskedPng(deadline),environment,tolerance,maximumRatio));
    }
    public void performance(Map<String,Double> budgets) {
        qualityModule("performance",() -> { Failure.require(engine() instanceof PlaywrightEngine,"AUTOMATION_UNSUPPORTED","Performance needs a web engine");var metrics=((PlaywrightEngine)engine()).performanceMetrics(deadline);evidence.event(Map.of("kind","performance-metrics","metrics",metrics));hu.ak91.sf.e2e.core.quality.QualityModules.performance(metrics,budgets); });
    }
    private void qualityModule(String module,Runnable check) {
        try { check.run();evidence.event(Map.of("kind","quality-module","module",module,"status","PASSED")); }
        catch(RuntimeException e) { evidence.event(Map.of("kind","quality-module","module",module,"status","FAILED","code",code(e)));throw e; }
    }
    /** Deterministic custom business predicate, polled without repeating an action. Exceptions remain failures. */
    public void expect(String description,java.util.function.BooleanSupplier condition) {
        Failure.require(description!=null && !description.isBlank() && description.length()<=1000,"CONFIG_INVALID","Describe the expected business outcome");
        var wait=deadline.child(Duration.ofSeconds(config.execution().assertionTimeoutSeconds()));
        while(true) {
            try {
                wait.check();if(condition.getAsBoolean()) { evidence.event(Map.of("kind","assertion-passed","assertion","custom","description",description));return; }
                engine().poll(wait);
            } catch(Failure f) { if(f.code().equals("STEP_TIMEOUT")) throw new Failure("UI_ASSERTION_FAILED",secrets.redact("Expected outcome not satisfied: "+description));throw f; }
        }
    }
    /** Default POM API, isolated by persona and session; assertions stay in the consumer case. */
    public PageObjects pages() { return pages.computeIfAbsent(persona,p -> new PageObjects(locators(),deadline)); }
    public PageObject page(String id) { return pages().page(id); }
    /** Low-level compatibility API. Prefer page(id) for new consumer cases. */
    public HealingLocators locators() {
        return locators.computeIfAbsent(persona,p -> {
            var store=new LocatorStore(Path.of(config.locators().dir()),LocatorStore.namespace(config,p),config.locators().mode());
            HealingLocators.Advisor advisor=config.locators().aiFallback()?new ModelHealingAdvisor(() -> {
                if(model==null) model=AiWorkflowClient.create(config.agent(),secrets,evidence::event);return model;
            },config.agent()):null;
            return new HealingLocators(sessions.get(p),store,config.locators(),deadline,evidence::event,advisor);
        });
    }
    public SfAgent agent() {
        Failure.require(!config.profile().equals("deterministic"),"POLICY_DENIED","The deterministic profile makes no model calls");
        Failure.require(info.tags().contains("ai"),"POLICY_DENIED","Model execution is only available to explicitly selected AI cases");
        return agents.computeIfAbsent(persona,p -> new SfAgent(sessions.get(p),() -> {
            if(model==null) model=AiWorkflowClient.create(config.agent(),secrets,evidence::event);return model;
        },config,p,info.id(),attemptId,deadline,evidence::event));
    }
    public ActScope editScope(hu.ak91.sf.e2e.salesforce.fixture.OwnedRecord record,String... fields) {
        records.owned(record);return new ActScope(attemptId,record.objectType,record.id(),Set.of(fields),"edit-dialog");
    }
    public ActScope recordScope(hu.ak91.sf.e2e.salesforce.fixture.OwnedRecord record,String route,String container,String... fields) {
        records.owned(record);return new ActScope(attemptId,record.objectType,record.id(),Set.of(fields),container,route);
    }
    public AttemptResult finish(Throwable error) {
        if(finished) return result;finished=true;
        Throwable original=error;List<String> secondary=new ArrayList<>();
        String evidenceVerdict="PASSED";
        if(original==null) try { deadline.check();for(var agent:agents.values()) agent.requireProofs(); } catch(Throwable e) { original=e; }
        boolean failureAlreadyPresent=original!=null;
        try {
            if(original==null) evidence.checkpoint("test-completion");
            else captureFailure(original);
        } catch(Throwable e) { secondary.add(secrets.redact(code(e)+": "+e.getMessage()));evidenceVerdict="FAILED"; }
        // Screenshot calls pump browser events. Check the gate after the final outcome capture,
        // so an error delivered during capture cannot leave a successful user body marked passed.
        for(var session:sessions.values()) try { BrowserQuality.verify(config.quality(),session.browserIssues(),evidence.events(),evidence::event); }
        catch(Throwable qualityFailure) { if(original==null) original=qualityFailure;else secondary.add(secrets.redact(code(qualityFailure)+": "+qualityFailure.getMessage())); }
        if(!failureAlreadyPresent && original!=null) try { captureFailure(original); }
        catch(Throwable e) { secondary.add(secrets.redact(code(e)+": "+e.getMessage()));evidenceVerdict="FAILED"; }
        String business=original==null?"PASSED":classify(original),status=business;
        String code=original==null?null:code(original),message=original==null?null:secrets.redact(original.getMessage());
        try { records.persist(); } catch(Throwable e) { secondary.add(secrets.redact(code(e)+": "+e.getMessage()));evidenceVerdict="FAILED"; }
        if(business.equals("PASSED")) try { evidence.verifyMinimum(config.evidence().minPngPerPassedResult()); }
        catch(Throwable e) { secondary.add(secrets.redact(code(e)+": "+e.getMessage()));evidenceVerdict="FAILED";code=code(e);message=secrets.redact(e.getMessage()); }
        if(business.equals("PASSED") && evidenceVerdict.equals("FAILED")) status="EVIDENCE_FAILED";
        for(var session:sessions.values()) if(session instanceof PlaywrightEngine web) try { web.finishPrivateTrace(!status.equals("PASSED")); } catch(Throwable e) { secondary.add("Private diagnostics could not be finalized");evidenceVerdict="FAILED";if(status.equals("PASSED")) {status="EVIDENCE_FAILED";code="EVIDENCE_INCOMPLETE";} }
        try { close(); } catch(Throwable e) {
            secondary.add(secrets.redact(code(e)+": "+e.getMessage()));
            if(business.equals("PASSED")) { status="BLOCKED";code="ENGINE_FAILURE";message="Browser context finalization failed"; }
        }
        result=new AttemptResult(runId,attemptId,info,started,Instant.now(),business,evidenceVerdict,status,code,message,List.copyOf(secondary),evidence.artifacts(),evidence.events(),records.manifest());
        return result;
    }
    private void captureFailure(Throwable error) {
        evidence.event(Map.of("kind","failure","code",code(error),"message",secrets.redact(Objects.toString(error.getMessage(),"Failure")),"persona",persona));
        if(sessions.containsKey(persona)) evidence.checkpoint("failure-original-state");
        if(error instanceof Failure failure && Set.of("VALUE_MISMATCH","UI_ASSERTION_FAILED").contains(failure.code()))
            for(var agent:agents.values()) agent.invalidatePending();
    }
    String redact(String message) { return secrets.redact(message); }
    static String code(Throwable e) { return e instanceof Failure f?f.code():e instanceof org.opentest4j.TestAbortedException?"TEST_ABORTED":e instanceof AssertionError?"UI_ASSERTION_FAILED":"ENGINE_FAILURE"; }
    static String classify(Throwable e) {
        String code=code(e);
        if(code.equals("TEST_ABORTED")) return "SKIPPED";
        if(code.equals("CANCELLED")) return "CANCELLED";
        if(e instanceof AssertionError || Set.of("ASSERTION_FAILED","API_ASSERTION_FAILED","FILE_VERIFICATION_FAILED","APPLICATION_DEFECT","QUALITY_GATE_FAILED","UI_ASSERTION_FAILED","VALUE_MISMATCH","AGENT_LOOP_DETECTED","STEP_BUDGET_EXHAUSTED","STEP_TIMEOUT","TEST_TIMEOUT","END_MISMATCH","PROOF_MISSING").contains(code)) return "FAILED";
        return "BLOCKED";
    }
    @Override public void close() {RuntimeException failure=null;for(var session:sessions.values()) try {session.close();}catch(RuntimeException e) {if(failure==null) failure=e;else failure.addSuppressed(e);}if(failure!=null) throw failure;}
}
