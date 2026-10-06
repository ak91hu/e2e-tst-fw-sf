package hu.ak91.sf.e2e.core;

import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QualityAssertionsTest {
    @TempDir Path root;
    static class Engine implements UiEngine {
        List<Observation.Node> nodes=new ArrayList<>();boolean complete=true;String current="https://org/app";int polls,actions;
        public void open(String r,Deadline d) {}
        public String route() { return current; }
        public Observation observe(String c,Deadline d) { d.check();return new Observation(1,Instant.now(),current,nodes,false,complete?"COMPLETE":"PARTIAL","SAFE",null); }
        public Observation.Node resolve(SemanticTarget t,Observation o) { return o.nodes().getFirst(); }
        public void perform(Action a,Observation o,Deadline d) { actions++; }
        public Map<String,String> readVisibleFields(Deadline d) { return Map.of(); }
        public byte[] captureMaskedPng(Deadline d) { return new byte[0]; }
        public void poll(Deadline d) { polls++;d.check(); }
        public String capabilityVersion() { return "engine"; }public void close() {}
    }
    private LocatorStore store(boolean anchor) {
        var store=new LocatorStore(root.resolve(anchor?"anchored":"unanchored"),"namespace","read-write");store.putAll(List.of(new LocatorEntry("p","control","https://org/app","engine",SemanticTarget.role("button","Submit","page"),anchor?Map.of("testId","submit","frame","main"):Map.of(),List.of(),1,List.of())));return store;
    }
    private CaseRunner runner(Engine e,LocatorStore store) {
        var c=FrameworkConfig.defaults();var config=new FrameworkConfig(1,c.projectId(),c.profile(),c.org(),c.ui(),c.auth(),c.personas(),new FrameworkConfig.Execution(1,0,5,1,1,false),c.agent(),c.cache(),c.retention(),c.evidence(),c.report(),c.locators());
        return new CaseRunner(e,store,config,Deadline.after(Duration.ofSeconds(3)),event -> {});
    }
    private CaseRecipe.Step step(String kind,String ref) { return new CaseRecipe.Step(kind,"p","control",null,ref,null); }
    @Test void absenceRequiresStableIdentityCompleteCoverageAndTheCorrectRoute() {
        var e=new Engine();var unanchored=store(false);assertEquals("NEGATIVE_ASSERTION_UNPROVABLE",assertThrows(Failure.class,() -> runner(e,unanchored).execute(step("expectHidden",null),Map.of())).code());
        var disk=store(true);var r=runner(e,disk);e.complete=false;assertEquals("LOCATOR_INCOMPLETE",assertThrows(Failure.class,() -> r.execute(step("expectHidden",null),Map.of())).code());
        e.complete=true;e.current="https://org/other";assertEquals("LOCATOR_WRONG_CONTEXT",assertThrows(Failure.class,() -> r.execute(step("expectHidden",null),Map.of())).code());
    }
    @Test void renamedRoleChangedOrMovedControlCannotProduceAFakeAbsencePass() {
        var e=new Engine();var disk=store(true);e.nodes=List.of(new Observation.Node("new",SemanticTarget.role("link","Renamed","page"),"","",false,false,Map.of("testId","submit","frame","https://org/frame")));
        assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> runner(e,disk).execute(step("expectHidden",null),Map.of())).code());assertEquals(0,e.actions);
    }
    @Test void countRequiresConsecutiveObservationsAndNeverDispatchesAnAction() {
        var e=new Engine();var disk=store(true);e.nodes=List.of(new Observation.Node("1",SemanticTarget.role("button","Submit","page"),"","",false,false,Map.of("frame","main")),new Observation.Node("2",SemanticTarget.role("button","Submit","page"),"","",false,false,Map.of("frame","main")));
        runner(e,disk).execute(step("expectCount","count"),Map.of("count","2"));assertEquals(2,e.polls);assertEquals(0,e.actions);
        assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> runner(e,disk).execute(step("expectCount","count"),Map.of("count","1"))).code());
    }
    @Test void enabledAndDisabledCheckTheActualStateAndWrongExpectationsFail() {
        var e=new Engine();var disk=store(true);e.nodes=List.of(new Observation.Node("1",SemanticTarget.role("button","Submit","page"),"","",true,false,Map.of("testId","submit","frame","main")));
        runner(e,disk).execute(step("expectDisabled",null),Map.of());assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> runner(e,disk).execute(step("expectEnabled",null),Map.of())).code());
    }
    @Test void malformedCountsAndEmptySuiteSelectionFailBeforeBrowserWork() {
        var count=step("expectCount","count");for(String value:List.of("-1","1.5","10001","01","NaN")) assertThrows(Failure.class,() -> CaseRecipe.validateParams(Set.of("count"),Map.of("count",value),List.of(count)));
        var row=new CaseSuite.Row("valid","recipe.json",null,null,List.of("smoke"));var suite=new CaseSuite(1,"suite",List.of(row));
        assertEquals("NO_TESTS",assertThrows(Failure.class,() -> suite.select(Set.of("missing"),Set.of())).code());
        assertThrows(Failure.class,() -> new CaseSuite(1,"suite",List.of(row,row)));assertThrows(Failure.class,() -> suite.select(Set.of(),Set.of("missing")));
    }
    @Test void emptyVerificationOrAnUnobservedFieldCannotProduceOutcomeProof() {
        assertEquals("UI_ASSERTION_FAILED",assertThrows(Failure.class,() -> UiVerification.compare("attempt","record",Map.of(),Map.of(),"route","evidence")).code());
        assertEquals("VALUE_MISMATCH",assertThrows(Failure.class,() -> UiVerification.compare("attempt","record",Map.of("Required",""),Map.of(),"route","evidence")).code());
        var nullExpectation=new HashMap<String,Object>();nullExpectation.put("Required",null);
        assertThrows(Failure.class,() -> UiVerification.compare("attempt","record",nullExpectation,Map.of(),"route","evidence"));
    }
}
