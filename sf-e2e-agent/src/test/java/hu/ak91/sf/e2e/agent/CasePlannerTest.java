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
        return Map.of("steps",List.of(Map.of("kind","expectText","instruction","Result","valueRef","expected","sourceInstruction",1)),"parameters",Map.of("expected","READY"));
    }
    private static Deadline deadline() { return Deadline.after(Duration.ofSeconds(10)); }
    @Test void plansExplicitIntentWithoutObservationAndReturnsLiteralValuesSeparately() {
        var model=new ScriptedModelClient(request -> {assertEquals("case-plan",request.kind());assertNull(request.observation());return response(data());});
        var planner=new CasePlanner(model,FrameworkConfig.defaults().agent(),new SecretRegistry());
        var plan=planner.plan("intent","/app","Verify Result displays READY.",Set.of(),deadline());
        assertEquals("TST_intent",plan.spec().id());assertEquals(Map.of("expected","READY"),plan.parameters());
        assertFalse(Json.string(plan.spec()).contains("READY"));assertEquals(1,model.calls());
        assertEquals(List.of(1),plan.sourceSteps());assertEquals(1,plan.sourceInstructionCount());
    }
    @Test void protocolRepairRemainsInsideTheSharedModelBudget() {
        var model=new ScriptedModelClient(request -> new ModelClient.Response("click","field",null,null,null,null,null,List.of(),null,10L));
        var policy=new FrameworkConfig.Agent("http://localhost/model","fixture","UNUSED",2,1,1000,5);
        var planner=new CasePlanner(model,policy,new SecretRegistry());
        assertEquals("STEP_BUDGET_EXHAUSTED",assertThrows(Failure.class,() -> planner.plan("intent","/app","Verify Result is READY.",Set.of(),deadline())).code());
        assertEquals(1,model.calls());
    }
    @Test void incompleteIntentCannotBecomeAPassingPlan() {
        var model=new ScriptedModelClient(request -> response(Map.of("steps",List.of(Map.of("kind","click","instruction","Submit","sourceInstruction",1)))));
        var planner=new CasePlanner(model,FrameworkConfig.defaults().agent(),new SecretRegistry());
        assertThrows(Failure.class,() -> planner.plan("intent","/app","Click Submit.",Set.of(),deadline()));
    }
    @Test void omittedReorderedAndSkippedInstructionLinesCannotBecomePlans() {
        for(var sources:List.of(List.of(1,2),List.of(2,1,3),List.of(1,3))) {
            var model=new ScriptedModelClient(request -> response(Map.of("steps",sources.stream().map(source ->
                Map.of("kind","expectVisible","instruction","Fixture","sourceInstruction",source)).toList())));
            var planner=new CasePlanner(model,FrameworkConfig.defaults().agent(),new SecretRegistry());
            assertEquals("CASE_INSTRUCTION_MISMATCH",assertThrows(Failure.class,() -> planner.plan("ordered","/app","First instruction\nSecond instruction\nThird instruction",Set.of(),deadline())).code());
            assertEquals(1,model.calls());
        }
    }
    @Test void oneInstructionMayExpandIntoSeveralConsecutiveStepsAndBlankLinesDoNotCreateGaps() {
        var model=new ScriptedModelClient(request -> {
            assertEquals(List.of("Enter input and check it.","Verify result."),request.params().get("sourceInstructions"));
            return response(Map.of("steps",List.of(
                Map.of("kind","fill","instruction","Input","valueRef","input","sourceInstruction",1),
                Map.of("kind","expectValue","instruction","Input","valueRef","expected","sourceInstruction",1),
                Map.of("kind","expectVisible","instruction","Result","sourceInstruction",2))));
        });
        var plan=new CasePlanner(model,FrameworkConfig.defaults().agent(),new SecretRegistry()).plan("ordered","/app","Enter input and check it.\r\n\r\nVerify result.",Set.of("input","expected"),deadline());
        assertEquals(List.of(1,1,2),plan.sourceSteps());assertEquals(3,plan.spec().steps().size());
        assertFalse(Json.string(plan.spec()).contains("sourceInstruction"));
    }
    @Test void missingOrOutOfRangeSourceReferencesAreRejectedByTheModelSchema() {
        for(Object source:List.of(0,2,"1")) {
            var model=new ScriptedModelClient(request -> response(Map.of("steps",List.of(Map.of("kind","expectVisible","instruction","Result","sourceInstruction",source)))));
            assertEquals("MODEL_OUTPUT_INVALID",assertThrows(Failure.class,() -> new CasePlanner(model,FrameworkConfig.defaults().agent(),new SecretRegistry()).plan("invalid","/app","Verify result.",Set.of(),deadline())).code());
            assertEquals(2,model.calls());
        }
        var missing=new ScriptedModelClient(request -> response(Map.of("steps",List.of(Map.of("kind","expectVisible","instruction","Result")))));
        assertEquals("MODEL_OUTPUT_INVALID",assertThrows(Failure.class,() -> new CasePlanner(missing,FrameworkConfig.defaults().agent(),new SecretRegistry()).plan("invalid","/app","Verify result.",Set.of(),deadline())).code());
    }
}
