package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiRequestEfficiencyTest {
    AiWorkflowClientTest fixture=new AiWorkflowClientTest();
    @Test void compactDiscoveryPreservesIdentityStateSchemaAndNonemptyEvidence() throws Exception {
        var policy=FrameworkConfig.defaults().agent();var request=fixture.request("Identify current input","Reference");
        var full=JsonModelClient.body(policy,request,false);var compact=JsonModelClient.body(policy,request,true);
        assertTrue(Json.string(compact).length()<Json.string(full).length());
        var messages=(List<?>)compact.get("messages");var data=Json.MAPPER.readTree(((Map<?,?>)messages.get(1)).get("content").toString());
        assertEquals(request.schema(),data.get("schema"));assertEquals(Json.MAPPER.valueToTree(request.params()),data.get("params"));
        var node=data.path("observation").path("nodes").get(0);
        assertEquals("n1",node.path("id").asText());assertEquals("Reference",node.path("target").path("name").asText());
        assertEquals(Json.MAPPER.valueToTree(request.observation().nodes().getFirst().attributes()),node.get("attributes"));
        assertTrue(node.has("disabled"));assertTrue(node.has("checked"));assertFalse(data.path("observation").has("capturedAt"));
        var live=new ModelClient.Request("assert","Check expected value",Map.of("expected","SUPPLIED"),request.observation(),List.of(Map.of("verified",true)),null);
        assertEquals(JsonModelClient.body(policy,live,false),JsonModelClient.body(policy,live,true));
    }
    @Test void singleTargetReservesLessOutputAndLiveJudgmentKeepsTheConfiguredAllowance() {
        var policy=FrameworkConfig.defaults().agent();var target=fixture.request("Identify","Reference");
        assertEquals(768,JsonModelClient.body(policy,target).get("max_completion_tokens"));
        assertEquals(4096,JsonModelClient.outputLimit(policy,new ModelClient.Request("assert","Check",Map.of(),null,List.of(),null)));
        var provider=new ScriptedModelClient(r -> fixture.response("n1",10L));var events=new ArrayList<Map<String,Object>>();
        var workflow=new AiWorkflowClient(policy,() -> provider,() -> provider,events::add);workflow.complete(target,fixture.deadline());
        assertTrue(((Number)workflow.snapshot().get("compactBytesSaved")).longValue()>0);
        assertEquals(768L,workflow.snapshot().get("outputTokensReserved"));assertEquals(1,provider.calls());
    }
    @Test void cappedReadOnlyResponseGetsOneFullAllowanceAttemptWithoutStrongerTier() {
        var policy=FrameworkConfig.defaults().agent();var attempts=new ArrayList<Integer>();
        var provider=new ScriptedModelClient(r -> {
            attempts.add(JsonModelClient.outputLimit(policy,r));
            if(!r.fullOutput()) throw new Failure("MODEL_OUTPUT_LIMIT","Truncated");return fixture.response("n1",10L);
        });
        var workflow=new AiWorkflowClient(policy,() -> provider,() -> {throw new AssertionError();},e -> {});
        assertTrue(workflow.complete(fixture.request("Identify","Reference"),fixture.deadline()).tokens()>10);
        assertEquals(List.of(768,4096),attempts);assertEquals(2,provider.calls());assertEquals(1,workflow.snapshot().get("unknownUsageCalls"));
    }
    @Test void alreadyFullOutputCannotWasteAnotherIdenticalPaidAttempt() {
        var policy=FrameworkConfig.defaults().agent();var provider=new ScriptedModelClient(r -> {throw new Failure("MODEL_OUTPUT_LIMIT","Truncated");});
        var workflow=new AiWorkflowClient(policy,() -> provider,() -> provider,e -> {});
        var request=new ModelClient.Request("case-plan","Plan",Map.of(),null,List.of(),Json.MAPPER.valueToTree(Map.of("type","object")));
        assertEquals("AI_WORKFLOW_REJECTED",assertThrows(Failure.class,() -> workflow.complete(request,fixture.deadline())).code());assertEquals(1,provider.calls());
    }
    @Test void optionalOversizedRequestDoesNotChargeOrStopTheWorkflow() {
        var policy=FrameworkConfig.defaults().agent();var provider=new ScriptedModelClient(r -> fixture.response("n1",10L));
        var workflow=new AiWorkflowClient(policy,() -> provider,() -> provider,e -> {});
        var oversized=fixture.request("x".repeat(policy.workflow().maxInputBytes()),"Reference");
        assertFalse(workflow.canReserve(oversized));assertEquals(0,provider.calls());assertEquals(0L,workflow.snapshot().get("chargedTokens"));
        assertEquals(false,workflow.snapshot().get("stopped"));assertTrue(workflow.canReserve(fixture.request("Identify","Reference")));
        workflow.complete(fixture.request("Identify","Reference"),fixture.deadline());assertEquals(1,provider.calls());
    }
}
