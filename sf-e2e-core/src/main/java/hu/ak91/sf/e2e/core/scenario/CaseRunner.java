package hu.ak91.sf.e2e.core.scenario;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.page.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** No dependency on the agent module, model credentials, or AI verdicts. */
public final class CaseRunner {
    private final UiEngine engine;
    private final PageObjects pages;
    private final Deadline deadline;
    private final int assertionSeconds;
    private final Consumer<Map<String,Object>> events;
    public CaseRunner(UiEngine engine,LocatorStore store,FrameworkConfig config,Deadline deadline,Consumer<Map<String,Object>> events) {
        this.engine=engine;this.deadline=deadline;this.events=events;assertionSeconds=config.execution().assertionTimeoutSeconds();
        var p=config.locators();
        // Even a manually constructed mapping config cannot enable model use during recipe execution.
        pages=new PageObjects(new HealingLocators(engine,store,new FrameworkConfig.Locators(p.dir(),p.mode(),p.healing(),p.strict(),false,p.retries(),p.retryDelayMillis(),p.timeoutSeconds(),p.minConfidence(),p.minMargin()),deadline,events),deadline);
    }
    public void run(CaseRecipe recipe,String namespace,LocatorStore store,Map<String,String> params) {
        recipe.preflight(namespace,store,params);
        Failure.require(recipe.engineVersion().equals(engine.capabilityVersion()),"LOCATOR_ENGINE_CHANGED","Learn the case again after an engine upgrade");
        events.accept(Map.of("kind","case-replay-plan","executionModel","page-object-model","pageObjects",recipe.pageObjects(),"steps",recipe.steps()));
        engine.open(recipe.startRoute(),deadline);
        int index=0;
        for(var step:recipe.steps()) {
            events.accept(Map.of("kind","case-step-started","step",++index,"action",step.kind()));
            hu.ak91.sf.e2e.core.quality.ExecutionMetrics.measure("case-step",index,events,() -> execute(step,params));events.accept(Map.of("kind","case-step-passed","step",index,"action",step.kind()));
        }
    }
    public void execute(CaseRecipe.Step step,Map<String,String> params) {
        deadline.check();String value=step.valueRef()==null?null:params.get(step.valueRef());
        if(step.kind().equals("switchPage")) { engine.switchPage(value,deadline);return; }
        if(step.kind().equals("open")) { engine.open(step.route(),deadline);return; }
        if(step.kind().equals("expectRoute")) {
            Deadline wait=deadline.child(Duration.ofSeconds(assertionSeconds));
            while(true) {
                if(routeMatches(step.route(),engine.route())) { assertionPassed(step);return; }
                assertionPoll(wait);continue;
            }
        }
        var page=pages.page(step.page());
        var binding=step.recordNameRef()==null?Map.<String,String>of():Map.of("recordName",params.get(step.recordNameRef()));
        if(!CaseSpec.assertion(step.kind())) { page.action(step.key(),step.kind(),value,binding);return; }
        Deadline wait=deadline.child(Duration.ofSeconds(assertionSeconds));
        if(Set.of("expectHidden","expectCount").contains(step.kind())) {
            long expected=step.kind().equals("expectHidden")?0:Long.parseLong(value);int stable=0;
            while(true) {
                long actual;
                try { actual=page.visibleCount(step.key(),binding,wait,expected==0); }
                catch(Failure failure) { if(failure.code().equals("STEP_TIMEOUT")) throw new Failure("ASSERTION_FAILED","Count/absence expectation was not satisfied within its deadline");throw failure; }
                stable=actual==expected?stable+1:0;
                if(stable>=3) { assertionPassed(step);return; }
                assertionPoll(wait);
            }
        }
        while(true) {
            Observation.Node node;
            try { node=page.read(step.key(),binding,wait); }
            catch(Failure failure) {
                if(failure.code().equals("STEP_TIMEOUT")) throw new Failure("ASSERTION_FAILED","Case target was not available within its assertion deadline");
                if(Set.of("TARGET_NOT_FOUND","LOCATOR_WRONG_CONTEXT","LOCATOR_STALE","LOCATOR_LOW_CONFIDENCE").contains(failure.code())) { assertionPoll(wait);continue; }
                throw failure;
            }
            boolean passed=switch(step.kind()) {
                case "expectVisible" -> true;
                case "expectText" -> Objects.equals(value,node.text());
                case "expectValue" -> Objects.equals(value,node.value());
                case "expectChecked" -> node.checked()==Boolean.parseBoolean(value);
                case "expectEnabled" -> !node.disabled();
                case "expectDisabled" -> node.disabled();
                default -> throw new Failure("CASE_INVALID","Unsupported assertion");
            };
            if(passed) { assertionPassed(step);return; }
            assertionPoll(wait);
        }
    }
    private void assertionPassed(CaseRecipe.Step step) {
        var event=new LinkedHashMap<String,Object>();event.put("kind","assertion-passed");event.put("assertion",step.kind());
        if(step.page()!=null) { event.put("page",step.page());event.put("key",step.key()); }events.accept(event);
    }
    private void assertionPoll(Deadline wait) {
        try { wait.check();engine.poll(wait); }
        catch(Failure failure) { if(failure.code().equals("STEP_TIMEOUT")) throw new Failure("ASSERTION_FAILED","Case expectation was not satisfied within its deadline");throw failure; }
    }
    private static boolean routeMatches(String expected,String actual) {
        URI route=URI.create(actual.replace("[REDACTED]","%5BREDACTED%5D"));
        return expected.equals(route.getRawPath()+(route.getRawQuery()==null?"":"?"+route.getRawQuery())+(route.getRawFragment()==null?"":"#"+route.getRawFragment()));
    }
}
