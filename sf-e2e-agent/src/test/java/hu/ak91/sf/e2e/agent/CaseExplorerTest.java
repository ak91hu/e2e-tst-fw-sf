package hu.ak91.sf.e2e.agent;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CaseExplorerTest {
    final CaseSpec.Step oracle=new CaseSpec.Step("expectText","Result",null,null,"expected",null);
    ExplorationCharter charter() {return new ExplorationCharter(1,"fixture","Verify supplied result",List.of("/app"),Set.of("click"),Set.of("Submit"),List.of(oracle),2,5);}
    CaseSpec spec(String instruction) {return new CaseSpec("fixture","/app",List.of(new CaseSpec.Step("click",instruction,null,null,null,null),oracle));}
    ModelClient.Response response(List<CaseSpec> cases) {return new ModelClient.Response("finish",null,null,null,"PASSED","Fixture plan",null,List.of(),Json.MAPPER.valueToTree(Map.of("cases",cases)),10L);}
    @Test void plansFromFreshControlsWithoutValuesTextOrBusinessDispatch() {
        var engine=new Engine();var model=new ScriptedModelClient(r -> {
            assertEquals("explore-cases",r.kind());assertNull(r.observation());String request=Json.string(r);
            assertFalse(request.contains("PRIVATE_FIELD_VALUE"));assertFalse(request.contains("PRIVATE_BODY_TEXT"));return response(List.of(spec("Submit")));
        });
        var plans=new CaseExplorer(engine,model,FrameworkConfig.defaults().agent(),new SecretRegistry()).plan(charter(),Set.of("expected"),Deadline.after(Duration.ofSeconds(5)));
        assertEquals(1,plans.size());assertEquals(1,model.calls());assertEquals(List.of("/app"),engine.opened);
    }
    @Test void modelCannotExpandAuthorizationOrDuplicateWorkflows() {
        for(var candidates:List.of(List.of(spec("Delete everything")),List.of(spec("Submit"),spec("Submit")))) {
            var model=new ScriptedModelClient(r -> response(candidates));
            assertThrows(Failure.class,() -> new CaseExplorer(new Engine(),model,FrameworkConfig.defaults().agent(),new SecretRegistry()).plan(charter(),Set.of("expected"),Deadline.after(Duration.ofSeconds(5))));
        }
    }
    @Test void unstableIncompleteSurfacesCannotTriggerPlanning() {
        var engine=new Engine();engine.complete=false;var model=new ScriptedModelClient(r -> {fail("No model call before complete observation");return null;});
        assertThrows(Failure.class,() -> new CaseExplorer(engine,model,FrameworkConfig.defaults().agent(),new SecretRegistry()).plan(charter(),Set.of("expected"),Deadline.after(Duration.ofMillis(50))));assertEquals(0,model.calls());
    }
    static final class Engine implements UiEngine {
        final List<String> opened=new ArrayList<>();boolean complete=true;
        public void open(String r,Deadline d) {opened.add(r);}public String route() {return "https://application.example/app";}
        public Observation observe(String scope,Deadline d) {return new Observation(1,Instant.now(),route(),List.of(new Observation.Node("n",SemanticTarget.role("button","Submit","page"),"PRIVATE_BODY_TEXT","PRIVATE_FIELD_VALUE",false,false)),!complete,complete?"COMPLETE":"PARTIAL","SAFE",null);}
        public Observation.Node resolve(SemanticTarget t,Observation o) {throw new UnsupportedOperationException();}
        public void perform(Action a,Observation o,Deadline d) {fail("Planning cannot dispatch business actions");}
        public Map<String,String> readVisibleFields(Deadline d) {throw new UnsupportedOperationException();}public byte[] captureMaskedPng(Deadline d) {throw new UnsupportedOperationException();}
        public void poll(Deadline d) {d.check();}public String capabilityVersion() {return "fixture";}public void close() {}
    }
}
