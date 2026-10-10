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

class CaseLearnerTest {
    @TempDir Path root;
    private final Deadline deadline=Deadline.after(Duration.ofSeconds(10));
    private static class Engine implements UiEngine {
        List<Observation.Node> nodes=List.of(new Observation.Node("field",new SemanticTarget("textbox","Name","Name","page"),"PRIVATE_UI_VALUE","PRIVATE_UI_VALUE",false,false,Map.of("tag","input","apiName","Flexible__c")));
        int actions,opens;
        public Observation observe(String scope,Deadline d) { return new Observation(1,Instant.now(),route(),nodes,false,"COMPLETE","SAFE",null); }
        public String capabilityVersion() { return "harness"; }
        public String route() { return "https://org/app"; }
        public void open(String r,Deadline d) { opens++; }
        public void perform(Action a,Observation o,Deadline d) { actions++; }
        public Observation.Node resolve(SemanticTarget t,Observation o) { return o.nodes().getFirst(); }
        public Map<String,String> readVisibleFields(Deadline d) { return Map.of(); }
        public byte[] captureMaskedPng(Deadline d) { return new byte[0]; }
        public void poll(Deadline d) { d.check(); }
        public void close() {}
    }
    private CaseSpec spec() { return new CaseSpec("user-case","/app",List.of(new CaseSpec.Step("expectVisible","Name",null,null,null,null))); }
    private ModelClient.Response response(Object data) { return new ModelClient.Response("finish",null,null,null,"PASSED","Observed",null,List.of(),Json.MAPPER.valueToTree(data),10L); }
    private CaseLearner learner(Engine e,LocatorStore s,ModelClient m) { return new CaseLearner(e,s,m,FrameworkConfig.defaults(),new SecretRegistry(),event -> {}); }
    private LocatorStore store() { return new LocatorStore(root,"test","read-write"); }
    @Test void malformedActionResponseGetsOnlyOneFormatRetryAndNeverDispatches() {
        var engine=new Engine();var disk=store();
        var model=new ScriptedModelClient(r -> new ModelClient.Response("click","field",null,null,null,null,null,List.of(),null,10L));
        assertEquals("MODEL_OUTPUT_INVALID",assertThrows(Failure.class,() -> learner(engine,disk,model).learn(spec(),"test",Map.of(),deadline)).code());
        assertEquals(2,model.calls());assertEquals(0,engine.actions);assertTrue(disk.entries().isEmpty());
    }
    @Test void inventedTargetCannotBeWrittenOrExecuted() {
        var engine=new Engine();var disk=store();var model=new ScriptedModelClient(r -> response(Map.of("nodeId","invented")));
        assertEquals("MODEL_OUTPUT_INVALID",assertThrows(Failure.class,() -> learner(engine,disk,model).learn(spec(),"test",Map.of(),deadline)).code());
        assertEquals(0,engine.actions);assertTrue(disk.entries().isEmpty());
    }
    @Test void changedUiDuringModelCallCannotDispatchOrWriteALocator() {
        var engine=new Engine();var disk=store();var model=new ScriptedModelClient(r -> {engine.nodes=List.of();return response(Map.of("nodeId","field"));});
        assertEquals("MAPPING_UI_CHANGED",assertThrows(Failure.class,() -> learner(engine,disk,model).learn(spec(),"test",Map.of(),deadline)).code());
        assertTrue(disk.entries().isEmpty());assertEquals(0,engine.actions);
    }
    @Test void valuesAreNotSentToTheModelAndFailedRelearningPreservesTheOldRecipe() {
        var engine=new Engine();var disk=store();
        var successful=new ScriptedModelClient(r -> { assertFalse(Json.string(r).contains("PRIVATE_UI_VALUE"));return response(Map.of("nodeId","field")); });
        var recipe=learner(engine,disk,successful).learn(spec(),"test",Map.of(),deadline);var original=Map.copyOf(disk.entries());
        var originalNodes=engine.nodes;
        engine.nodes=List.of(new Observation.Node("field",new SemanticTarget("textbox","Changed name","Changed name","page"),"","",false,false,Map.of("tag","input","apiName","Changed__c")));
        var failed=new ScriptedModelClient(r -> response(Map.of("nodeId","unknown")));
        assertThrows(Failure.class,() -> learner(engine,disk,failed).learn(spec(),"test",Map.of(),deadline));
        assertEquals(original,disk.entries());engine.nodes=originalNodes;new CaseRunner(engine,disk,FrameworkConfig.defaults(),deadline,e -> {}).run(recipe,"test",disk,Map.of());
    }
    @Test void generatedPlanCannotInventParameterReferences() {
        var engine=new Engine();var disk=store();
        var model=new ScriptedModelClient(r -> response(Map.of("steps",List.of(Map.of("kind","expectText","instruction","Result","valueRef","invented","sourceInstruction",1)))));
        assertEquals("CASE_PARAMETER_REQUIRED",assertThrows(Failure.class,() -> learner(engine,disk,model).plan("user-case","/app","Check result ${expected}",Set.of("expected"),deadline)).code());
        assertEquals(0,engine.opens);assertEquals(0,engine.actions);
    }
    @Test void textLiteralParametersAreReturnedSeparatelyAndNeverInTheRecipe() {
        var engine=new Engine();var disk=store();
        var model=new ScriptedModelClient(r -> response(Map.of("steps",List.of(Map.of("kind","expectText","instruction","Result","valueRef","expected","sourceInstruction",1)),"parameters",Map.of("expected","READY"))));
        var result=learner(engine,disk,model).planWithParameters("user-case","/app","Verify that Result displays exactly \"READY\".",Set.of(),deadline);
        assertEquals(Map.of("expected","READY"),result.parameters());assertEquals("expected",result.spec().steps().getFirst().valueRef());
        assertFalse(Json.string(result.spec()).contains("READY"));assertEquals(0,engine.actions);
    }
    @Test void textPlannerCannotInventLiteralValuesOrOverrideCallerParameters() {
        var engine=new Engine();var disk=store();
        var invented=new ScriptedModelClient(r -> response(Map.of("steps",List.of(Map.of("kind","expectText","instruction","Result","valueRef","expected","sourceInstruction",1)),"parameters",Map.of("expected","INVENTED"))));
        assertEquals("MODEL_OUTPUT_INVALID",assertThrows(Failure.class,() -> learner(engine,disk,invented).planWithParameters("user-case","/app","Expect READY",Set.of(),deadline)).code());
        var override=new ScriptedModelClient(r -> response(Map.of("steps",List.of(Map.of("kind","expectText","instruction","Result","valueRef","expected","sourceInstruction",1)),"parameters",Map.of("expected","READY"))));
        assertEquals("MODEL_OUTPUT_INVALID",assertThrows(Failure.class,() -> learner(engine,disk,override).planWithParameters("user-case","/app","Expect READY",Set.of("expected"),deadline)).code());
        assertEquals(0,engine.actions);
    }
    @Test void recordNameIsBoundAtRuntimeAndNeverSavedOrPassedAsObservedBusinessText() throws Exception {
        var engine=new Engine();engine.nodes=List.of(new Observation.Node("record",SemanticTarget.role("link","PRIVATE_RECORD","page"),"PRIVATE_RECORD","",false,false,
            Map.of("tag","a","frame","main","recordLink","true","recordRow","true","group","PRIVATE_RECORD")));
        var disk=store();var model=new ScriptedModelClient(r -> {assertFalse(Json.string(r).contains("PRIVATE_RECORD"));return response(Map.of("nodeId","record"));});
        var plan=new CaseSpec("record-case","/app",List.of(new CaseSpec.Step("expectVisible","Requested record",null,null,null,"record")));
        var recipe=learner(engine,disk,model).learn(plan,"test",Map.of("record","PRIVATE_RECORD"),deadline);
        assertTrue(disk.get(recipe.steps().getFirst().page(),recipe.steps().getFirst().key()).parameterized());
        assertFalse(Files.readString(disk.path()).contains("PRIVATE_RECORD"));assertFalse(Json.string(recipe).contains("PRIVATE_RECORD"));
    }
    @Test void budgetExhaustionStopsLearningBeforeAnotherAction() {
        var engine=new Engine();var disk=store();var d=FrameworkConfig.defaults();
        var policy=new FrameworkConfig.Agent(d.agent().endpoint(),"harness","UNUSED",2,1,1000,5);
        var config=new FrameworkConfig(1,"test","checks",d.org(),d.ui(),d.auth(),d.personas(),d.execution(),policy,d.cache(),d.retention(),d.evidence(),d.report());
        var model=new ScriptedModelClient(r -> response(Map.of("nodeId","field")));
        var plan=new CaseSpec("budget-case","/app",List.of(new CaseSpec.Step("expectVisible","Name",null,null,null,null),new CaseSpec.Step("expectVisible","Another control",null,null,null,null)));
        assertEquals("STEP_BUDGET_EXHAUSTED",assertThrows(Failure.class,() -> new CaseLearner(engine,disk,model,config,new SecretRegistry(),e -> {}).learn(plan,"test",Map.of(),deadline)).code());
        assertEquals(1,model.calls());assertEquals(0,engine.actions);
    }
}
