package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Intent planning uses only a bounded model protocol, without any UI engine or target session. */
class CasePlannerTest {
    private static ModelClient.Response response(Object data) {
        return new ModelClient.Response("finish",null,null,null,"PASSED","Planned intent",null,List.of(),Json.MAPPER.valueToTree(data),10L);
    }
    private static Map<String,Object> data() {
        return Map.of("steps",List.of(Map.of("kind","expectText","instruction","Result","valueRef","expected")),"parameters",Map.of("expected","READY"));
    }
    private static Deadline deadline() { return Deadline.after(Duration.ofSeconds(10)); }
    @Test void plansExplicitIntentWithoutObservationAndReturnsLiteralValuesSeparately() {
        var model=new ScriptedModelClient(request -> {assertEquals("case-plan",request.kind());assertNull(request.observation());return response(data());});
        var planner=new CasePlanner(model,FrameworkConfig.defaults().agent(),new SecretRegistry());
        var plan=planner.plan("intent","/app","Verify Result displays READY.",Set.of(),deadline());
        assertEquals("TST_intent",plan.spec().id());assertEquals(Map.of("expected","READY"),plan.parameters());
        assertFalse(Json.string(plan.spec()).contains("READY"));assertEquals(1,model.calls());
    }
    @Test void protocolRepairRemainsInsideTheSharedModelBudget() {
        var model=new ScriptedModelClient(request -> new ModelClient.Response("click","field",null,null,null,null,null,List.of(),null,10L));
        var policy=new FrameworkConfig.Agent("http://localhost/model","fixture","UNUSED",2,1,1000,5);
        var planner=new CasePlanner(model,policy,new SecretRegistry());
        assertEquals("STEP_BUDGET_EXHAUSTED",assertThrows(Failure.class,() -> planner.plan("intent","/app","Verify Result is READY.",Set.of(),deadline())).code());
        assertEquals(1,model.calls());
    }
    @Test void incompleteIntentCannotBecomeAPassingPlan() {
        var model=new ScriptedModelClient(request -> response(Map.of("steps",List.of(Map.of("kind","click","instruction","Submit")))));
        var planner=new CasePlanner(model,FrameworkConfig.defaults().agent(),new SecretRegistry());
        assertThrows(Failure.class,() -> planner.plan("intent","/app","Click Submit.",Set.of(),deadline()));
    }
}
