package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CaseMaintenanceTest {
    @TempDir Path root;
    private static class Engine implements UiEngine {
        String label="Alpha",save="Save",value="",beta="",result="",coverage="COMPLETE",capability="fixture";int opens,fills,saves;boolean uncertain;
        public String route() {return "https://fixture/app";}
        public String capabilityVersion() {return capability;}
        public void open(String route,Deadline deadline) {opens++;}
        public Observation observe(String scope,Deadline deadline) {
            return new Observation(1,Instant.now(),route(),List.of(
                node("alpha","textbox",label,"Alpha__c",value),node("beta","textbox","Beta","Beta__c",beta),
                node("save","button",save,"save",""),node("result","status","Result","result",result)),false,coverage,"SAFE",null);
        }
        private Observation.Node node(String id,String role,String name,String anchor,String value) {
            return new Observation.Node(id,new SemanticTarget(role,name,role.equals("textbox")?name:null,"page"),value,value,false,false,
                Map.of("tag",role.equals("textbox")?"input":role.equals("button")?"button":"output","apiName",anchor,"frame","main"));
        }
        public void perform(Action action,Observation observation,Deadline deadline) {
            if(action.kind().equals("fill")) {fills++;if(action.nodeId().equals("alpha")) value=action.value();else beta=action.value();}
            else if(action.kind().equals("click")) {saves++;result=value;if(uncertain) throw new Failure("ACTION_COMMIT_UNCERTAIN","Synthetic save may have committed");}
        }
        public Observation.Node resolve(SemanticTarget target,Observation observation) {return observation.nodes().stream().filter(n -> LocatorEntry.matches(target,n.target())).findFirst().orElseThrow();}
        public Map<String,String> readVisibleFields(Deadline deadline) {return Map.of();}
        public byte[] captureMaskedPng(Deadline deadline) {return new byte[0];}
        public void poll(Deadline deadline) {deadline.check();}
        public void close() {}
    }
    private Deadline deadline() {return Deadline.after(Duration.ofSeconds(8));}
    private FrameworkConfig config(String profile) {
        var c=FrameworkConfig.defaults();return new FrameworkConfig(1,c.projectId(),profile,c.org(),c.ui(),c.auth(),c.personas(),
            new FrameworkConfig.Execution(1,0,20,1,1,false),c.agent(),c.cache(),c.retention(),c.evidence(),c.report(),
            new FrameworkConfig.Locators(root.toString(),"read-write",true,false,false,0,0,2,.90,.10));
    }
    private LocatorStore store() {return new LocatorStore(root,"isolated","read-write");}
    private CaseRecipe seed(Engine engine,LocatorStore store) {
        var observation=engine.observe("page",deadline());
        for(var node:observation.nodes()) if(!node.id().equals("beta")) store.putAll(List.of(LocatorEntry.mapped("shared",node.id(),observation,node,"fixture")));
        new CaseTargetIndex(store).remember(new CaseSpec.Step("fill","The field labelled Alpha",null,null,"input",null),store.get("shared","alpha"));
        return new CaseRecipe(1,"historical-case",Json.hash("isolated"),"fixture","/app",List.of(
            new CaseRecipe.Step("fill","shared","alpha",null,"input",null),new CaseRecipe.Step("click","shared","save",null,null,null),
            new CaseRecipe.Step("expectText","shared","result",null,"expected",null)));
    }
    private Map<String,String> values() {return Map.of("input","PRIVATE_INPUT","expected","PRIVATE_INPUT","beta","PRIVATE_BETA","betaExpected","PRIVATE_BETA");}
    private CaseMaintenance.Plan additions(CaseRecipe baseline) {
        return CaseMaintenance.plan(baseline,new CaseMaintenance.Changes(1,Json.hash(baseline),List.of(new CaseMaintenance.Insertion(2,List.of(
            new CaseSpec.Step("fill","Beta",null,null,"beta",null),new CaseSpec.Step("expectValue","Beta",null,null,"betaExpected",null))))));
    }
    private CaseLearner learner(Engine engine,LocatorStore store,ModelClient model,String profile,List<Map<String,Object>> events) {
        return new CaseLearner(engine,store,model,config(profile),new SecretRegistry(),events::add);
    }
    private ScriptedModelClient model() {return new ScriptedModelClient(request -> {
        assertFalse(Json.string(request).contains("PRIVATE_"));assertFalse(Json.string(request.params()).contains("Retain stored control"));
        var node=request.observation().nodes().stream().filter(n -> "Beta".equals(n.target().name())).findFirst().orElseThrow();
        return new ModelClient.Response("finish",null,null,null,"PASSED","Synthetic observed Beta",null,List.of(),Json.MAPPER.valueToTree(Map.of("nodeId",node.id())),10L);
    });}
    @Test void locatorOnlyMaintenanceHealsSharedKeyPreservesHistoricalRecipeAndRetainsFreeFormAliases() throws Exception {
        var engine=new Engine();var store=store();var baseline=seed(engine,store);Path old=root.resolve("old.json");baseline.save(old);byte[] bytes=Files.readAllBytes(old);
        engine.label="Renamed Alpha";var events=new ArrayList<Map<String,Object>>();
        var maintained=learner(engine,store,null,"deterministic",events).maintain(CaseMaintenance.plan(baseline,null),"isolated",values(),deadline());
        assertEquals(baseline,maintained);assertArrayEquals(bytes,Files.readAllBytes(old));assertEquals(1,engine.fills);assertEquals(1,engine.saves);
        assertEquals(2,store.get("shared","alpha").revision());assertEquals(1,events.stream().filter(e -> "locator-healed".equals(e.get("kind"))).count());
        var unavailable=new ScriptedModelClient(request -> {throw new AssertionError("A repaired shared control must keep its existing intent aliases");});
        var next=new CaseSpec("next","/app",List.of(new CaseSpec.Step("expectVisible","The field labelled Alpha",null,null,null,null)));
        var reused=learner(engine,new LocatorStore(root,"isolated","read-write"),unavailable,"mapping",new ArrayList<>()).learn(next,"isolated",Map.of(),deadline());
        assertEquals("shared",reused.steps().getFirst().page());assertEquals("alpha",reused.steps().getFirst().key());assertEquals(0,unavailable.calls());
    }
    @Test void newFieldLearnsOnceAndAllRetainedOperationsExecuteOnceInMergedOrder() {
        var engine=new Engine();var store=store();var baseline=seed(engine,store);var provider=model();var events=new ArrayList<Map<String,Object>>();
        var maintained=learner(engine,store,provider,"mapping",events).maintain(additions(baseline),"isolated",values(),deadline());
        assertEquals(1,provider.calls());assertEquals(2,engine.fills);assertEquals(1,engine.saves);assertEquals(4,store.entries().size());
        assertEquals(baseline.steps(),List.of(maintained.steps().get(0),maintained.steps().get(3),maintained.steps().get(4)));
        assertEquals(maintained.steps().get(1).key(),maintained.steps().get(2).key());assertFalse(Json.string(maintained).contains("PRIVATE_"));
        assertEquals(List.of("fill","fill","expectValue","click","expectText"),events.stream().filter(e -> "case-step-learned".equals(e.get("kind"))).map(e -> e.get("action")).toList());
        var restart=model();learner(engine,new LocatorStore(root,"isolated","read-write"),restart,"mapping",new ArrayList<>()).maintain(additions(baseline),"isolated",values(),deadline());
        assertEquals(0,restart.calls());assertEquals(4,store.entries().size());
    }
    @Test void uncertainRetainedSaveIsNotRepeatedAndLaterNewStepsAreNotLearned() {
        var engine=new Engine();var store=store();var baseline=seed(engine,store);engine.uncertain=true;var provider=model();
        var plan=CaseMaintenance.plan(baseline,new CaseMaintenance.Changes(1,Json.hash(baseline),List.of(new CaseMaintenance.Insertion(3,List.of(
            new CaseSpec.Step("fill","Beta",null,null,"beta",null))))));
        var failure=assertThrows(Failure.class,() -> learner(engine,store,provider,"mapping",new ArrayList<>()).maintain(plan,"isolated",values(),deadline()));
        assertEquals("ACTION_COMMIT_UNCERTAIN",failure.code());assertEquals(1,engine.saves);assertEquals(1,engine.fills);assertEquals(0,provider.calls());assertEquals(3,store.entries().size());
    }
    @Test void wrongInsertedOracleFailsWithoutDispatchingRetainedSave() {
        var engine=new Engine();var store=store();var baseline=seed(engine,store);var params=new TreeMap<>(values());params.put("betaExpected","WRONG");
        assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> learner(engine,store,model(),"mapping",new ArrayList<>()).maintain(additions(baseline),"isolated",params,deadline())).code());
        assertEquals(2,engine.fills);assertEquals(0,engine.saves);
    }
    @Test void parametersNamespaceAndCapabilityFailBeforeOpeningOrDispatching() {
        var engine=new Engine();var store=store();var baseline=seed(engine,store);var learner=learner(engine,store,model(),"mapping",new ArrayList<>());
        assertEquals("CASE_PARAMETER_REQUIRED",assertThrows(Failure.class,() -> learner.maintain(additions(baseline),"isolated",Map.of(),deadline())).code());
        assertEquals("CASE_WRONG_CONTEXT",assertThrows(Failure.class,() -> learner.maintain(additions(baseline),"other",values(),deadline())).code());
        engine.capability="upgraded";
        assertEquals("LOCATOR_ENGINE_CHANGED",assertThrows(Failure.class,() -> learner.maintain(additions(baseline),"isolated",values(),deadline())).code());
        assertEquals(0,engine.opens);assertEquals(0,engine.fills);assertEquals(0,engine.saves);
    }
    @Test void incompleteObservationCannotRepairOrMutateRetainedControl() {
        var engine=new Engine();var store=store();var baseline=seed(engine,store);engine.coverage="PARTIAL";
        assertEquals("LOCATOR_INCOMPLETE",assertThrows(Failure.class,() -> learner(engine,store,null,"deterministic",new ArrayList<>()).maintain(CaseMaintenance.plan(baseline,null),"isolated",values(),deadline())).code());
        assertEquals(0,engine.fills);assertEquals(0,engine.saves);assertEquals(1,store.get("shared","alpha").revision());
    }
    @Test void aliasFinalizationFailureDoesNotMaskAnUncertainDispatchedMutation() throws Exception {
        var engine=new Engine();var store=store();var baseline=seed(engine,store);engine.save="Renamed Save";engine.uncertain=true;
        Files.writeString(store.path().resolveSibling(store.path().getFileName()+".target-index.json"),"corrupted");
        var failure=assertThrows(Failure.class,() -> learner(engine,store,null,"deterministic",new ArrayList<>()).maintain(CaseMaintenance.plan(baseline,null),"isolated",values(),deadline()));
        assertEquals("ACTION_COMMIT_UNCERTAIN",failure.code());assertEquals("CASE_TARGET_INDEX_INVALID",((Failure)failure.getSuppressed()[0]).code());
        assertEquals(1,engine.saves);assertEquals(2,store.get("shared","save").revision());
    }
}
