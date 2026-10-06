package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiWorkflowClientTest {
    static final com.fasterxml.jackson.databind.JsonNode SCHEMA=Json.MAPPER.valueToTree(Map.of("type","object","required",List.of("nodeId"),"additionalProperties",false,"properties",Map.of("nodeId",Map.of("type","string"))));
    ModelClient.Request request(String instruction,String name) {
        var node=new Observation.Node("n1",SemanticTarget.role("textbox",name,"page"),"","",false,false,Map.of("testId","field","frame","main"));
        return new ModelClient.Request("case-target",instruction,Map.of("kind","fill","target",name),new Observation(1,Instant.now(),"https://app.example/form",List.of(node),false,"COMPLETE","SAFE",null),List.of(),SCHEMA);
    }
    ModelClient.Response response(String node,Long tokens) { return new ModelClient.Response("finish",null,null,null,"PASSED","Current observed control",null,List.of(),Json.MAPPER.valueToTree(Map.of("nodeId",node)),tokens); }
    FrameworkConfig.Agent policy(int calls,long tokens,FrameworkConfig.AiWorkflow workflow) { return new FrameworkConfig.Agent("http://127.0.0.1/model","primary","KEY",40,calls,tokens,30,workflow); }
    Deadline deadline() {return Deadline.after(Duration.ofSeconds(10));}
    @Test void unchangedReadOnlyEvidenceReusesResponseButNewIdentityRequiresACall() {
        var provider=new ScriptedModelClient(r -> response("n1",20L));var events=new ArrayList<Map<String,Object>>();
        var workflow=new AiWorkflowClient(policy(2,100000,FrameworkConfig.AiWorkflow.defaults()),() -> provider,() -> {throw new AssertionError();},events::add);
        assertEquals(20L,workflow.complete(request("Identify","Reference"),deadline()).tokens());
        assertEquals(0L,workflow.complete(request("Identify","Reference"),deadline()).tokens());assertEquals(1,provider.calls());
        workflow.complete(request("Identify","Changed label"),deadline());assertEquals(2,provider.calls());
        assertEquals("AI_BUDGET_EXHAUSTED",assertThrows(Failure.class,() -> workflow.complete(request("Identify","Third label"),deadline())).code());
        assertEquals(2,provider.calls());assertEquals(1,workflow.snapshot().get("reusedResponses"));
    }
    @Test void liveJudgmentsAreNeverCachedAndBusinessFailureNeverEscalates() {
        var provider=new ScriptedModelClient(r -> new ModelClient.Response("finish",null,null,null,"FAILED","Outcome wrong","VALUE_MISMATCH",List.of(),null,10L));
        var workflow=new AiWorkflowClient(policy(5,100000,FrameworkConfig.AiWorkflow.defaults()),() -> provider,() -> {throw new AssertionError();},e -> {});
        var request=new ModelClient.Request("assert","Expected business result",Map.of(),null,List.of(),null);
        assertEquals("FAILED",workflow.complete(request,deadline()).verdict());assertEquals("FAILED",workflow.complete(request,deadline()).verdict());assertEquals(2,provider.calls());
        var blocked=new ModelClient.Request("case-target","Find target",Map.of(),null,List.of(),SCHEMA);
        assertEquals("FAILED",workflow.complete(blocked,deadline()).verdict());assertEquals(0,workflow.snapshot().get("escalations"));
    }
    @Test void invalidCurrentNodeEscalatesOnceAndAccountsForBothPhysicalCalls() {
        var primary=new ScriptedModelClient(r -> response("invented",10L));var strong=new ScriptedModelClient(r -> response("n1",30L));
        var w=FrameworkConfig.AiWorkflow.defaults();var tier=new FrameworkConfig.AiTier("http://127.0.0.1/strong","evaluated-strong","STRONG_KEY",null,null);
        var configured=new FrameworkConfig.AiWorkflow(true,w.maxInputBytes(),w.maxOutputTokens(),w.outputTokenParameter(),1,1,BigDecimal.ZERO,null,null,tier);
        var workflow=new AiWorkflowClient(policy(3,100000,configured),() -> primary,() -> strong,e -> {});
        assertEquals(40L,workflow.complete(request("Identify","Reference"),deadline()).tokens());assertEquals(1,primary.calls());assertEquals(1,strong.calls());
        assertEquals(1,workflow.snapshot().get("escalations"));assertEquals(2,workflow.snapshot().get("providerCalls"));
    }
    @Test void repeatedProtocolFailureStopsWithoutNestedRetryLoops() {
        var provider=new ScriptedModelClient(r -> response("invented",10L));var workflow=new AiWorkflowClient(policy(10,100000,FrameworkConfig.AiWorkflow.defaults()),() -> provider,() -> {throw new AssertionError();},e -> {});
        assertEquals("AI_WORKFLOW_REJECTED",assertThrows(Failure.class,() -> workflow.complete(request("Identify","Reference"),deadline())).code());assertEquals(2,provider.calls());
        assertEquals(20L,workflow.snapshot().get("chargedTokens"));
    }
    @Test void costReservationPreventsAnyDispatchAndPayloadsDoNotEnterEvents() {
        var w=FrameworkConfig.AiWorkflow.defaults();var limited=new FrameworkConfig.AiWorkflow(true,w.maxInputBytes(),w.maxOutputTokens(),w.outputTokenParameter(),1,0,new BigDecimal("0.000001"),BigDecimal.ONE,BigDecimal.TEN,null);
        var provider=new ScriptedModelClient(r -> response("n1",10L));var events=new ArrayList<Map<String,Object>>();
        var workflow=new AiWorkflowClient(policy(10,100000,limited),() -> provider,() -> {throw new AssertionError();},events::add);
        assertEquals("AI_BUDGET_EXHAUSTED",assertThrows(Failure.class,() -> workflow.complete(request("PRIVATE_PROMPT_SENTINEL","Reference"),deadline())).code());
        assertEquals(0,provider.calls());assertFalse(Json.string(events).contains("PRIVATE_PROMPT_SENTINEL"));
    }
    @Test void unknownUsageAndFailedTransportRetainReservationsRatherThanBecomingFree() {
        var provider=new ScriptedModelClient(r -> response("n1",null));var initial=request("Identify","Reference");
        long reservation=Json.string(JsonModelClient.body(policy(10,100000,FrameworkConfig.AiWorkflow.defaults()),initial)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1024+768;
        var workflow=new AiWorkflowClient(policy(10,reservation*2-1,FrameworkConfig.AiWorkflow.defaults()),() -> provider,() -> {throw new AssertionError();},e -> {});
        assertEquals(reservation,workflow.complete(initial,deadline()).tokens());assertEquals(1,workflow.snapshot().get("unknownUsageCalls"));assertNull(workflow.snapshot().get("estimatedCostUsd"));
        assertEquals("AI_BUDGET_EXHAUSTED",assertThrows(Failure.class,() -> workflow.complete(request("Different","Reference"),deadline())).code());assertEquals(1,provider.calls());
        var broken=new ScriptedModelClient(r -> {throw new Failure("MODEL_PROVIDER_FAILED","HTTP 429");});
        var failed=new AiWorkflowClient(policy(10,100000,FrameworkConfig.AiWorkflow.defaults()),() -> broken,() -> {throw new AssertionError();},e -> {});
        assertEquals("MODEL_PROVIDER_FAILED",assertThrows(Failure.class,() -> failed.complete(request("Identify","Reference"),deadline())).code());
        assertEquals(1,broken.calls());assertEquals(reservation,((Number)failed.snapshot().get("chargedTokens")).longValue());
    }
    @Test void overReportedOrInconsistentUsageStopsFurtherAiEvenWithRemainingCalls() {
        var provider=new ScriptedModelClient(r -> response("n1",100001L));var workflow=new AiWorkflowClient(policy(10,100000,FrameworkConfig.AiWorkflow.defaults()),() -> provider,() -> provider,e -> {});
        assertEquals("AI_BUDGET_EXHAUSTED",assertThrows(Failure.class,() -> workflow.complete(request("Identify","Reference"),deadline())).code());
        assertEquals("AI_BUDGET_EXHAUSTED",assertThrows(Failure.class,() -> workflow.complete(request("Other","Reference"),deadline())).code());assertEquals(1,provider.calls());
        var bad=new ScriptedModelClient(r -> new ModelClient.Response("finish",null,null,null,"PASSED","Read",null,List.of(),Json.MAPPER.valueToTree(Map.of("nodeId","n1")),20L,30L,-10L));
        var malformed=new AiWorkflowClient(policy(10,100000,FrameworkConfig.AiWorkflow.defaults()),() -> bad,() -> bad,e -> {});
        assertEquals("AI_USAGE_INVALID",assertThrows(Failure.class,() -> malformed.complete(request("Identify","Reference"),deadline())).code());assertEquals(1,bad.calls());
        var missingTotal=new ScriptedModelClient(r -> new ModelClient.Response("finish",null,null,null,"PASSED","Read",null,List.of(),Json.MAPPER.valueToTree(Map.of("nodeId","n1")),null,20L,5L));
        var incomplete=new AiWorkflowClient(policy(10,100000,FrameworkConfig.AiWorkflow.defaults()),() -> missingTotal,() -> missingTotal,e -> {});
        assertEquals("AI_USAGE_INVALID",assertThrows(Failure.class,() -> incomplete.complete(request("Identify","Reference"),deadline())).code());assertEquals(1,missingTotal.calls());
        var invalidTransport=new ScriptedModelClient(r -> {throw new Failure("AI_USAGE_INVALID","Invalid provider usage");});
        var rejectedUsage=new AiWorkflowClient(policy(10,100000,FrameworkConfig.AiWorkflow.defaults()),() -> invalidTransport,() -> invalidTransport,e -> {});
        assertEquals("AI_USAGE_INVALID",assertThrows(Failure.class,() -> rejectedUsage.complete(request("Identify","Reference"),deadline())).code());
        assertEquals("AI_BUDGET_EXHAUSTED",assertThrows(Failure.class,() -> rejectedUsage.complete(request("Identify","Reference"),deadline())).code());assertEquals(1,invalidTransport.calls());
    }
    @Test void oversizedEvidenceAndInvalidPriceConfigurationFailBeforeModelExecution() {
        var w=FrameworkConfig.AiWorkflow.defaults();var small=new FrameworkConfig.AiWorkflow(true,1024,w.maxOutputTokens(),w.outputTokenParameter(),1,0,BigDecimal.ZERO,null,null,null);
        var provider=new ScriptedModelClient(r -> response("n1",10L));var workflow=new AiWorkflowClient(policy(10,100000,small),() -> provider,() -> provider,e -> {});
        assertEquals("AI_CONTEXT_LIMIT",assertThrows(Failure.class,() -> workflow.complete(request("Identify","Reference"),deadline())).code());assertEquals(0,provider.calls());
        var invalid=new FrameworkConfig.AiWorkflow(true,1024,4096,"max_completion_tokens",1,0,BigDecimal.ONE,null,null,null);
        assertEquals("CONFIG_INVALID",assertThrows(Failure.class,invalid::validate).code());
    }
    @Test void providerTokenBreakdownProducesReviewedRateCostRatherThanAZeroOrReservedCost() {
        var w=FrameworkConfig.AiWorkflow.defaults();var priced=new FrameworkConfig.AiWorkflow(true,w.maxInputBytes(),w.maxOutputTokens(),w.outputTokenParameter(),1,0,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.TEN,null);
        var provider=new ScriptedModelClient(r -> new ModelClient.Response("finish",null,null,null,"PASSED","Read",null,List.of(),Json.MAPPER.valueToTree(Map.of("nodeId","n1")),25L,20L,5L));
        var workflow=new AiWorkflowClient(policy(10,100000,priced),() -> provider,() -> provider,e -> {});workflow.complete(request("Identify","Reference"),deadline());
        assertEquals(0,new BigDecimal("0.000070").compareTo((BigDecimal)workflow.snapshot().get("estimatedCostUsd")));assertEquals(25L,workflow.snapshot().get("chargedTokens"));
        var tier=new FrameworkConfig.AiTier("http://127.0.0.1/strong","strong","KEY",null,null);
        var mixed=new FrameworkConfig.AiWorkflow(true,w.maxInputBytes(),w.maxOutputTokens(),w.outputTokenParameter(),1,1,BigDecimal.ZERO,BigDecimal.ONE,BigDecimal.TEN,tier);
        var rejected=new ScriptedModelClient(r -> response("invented",10L));
        var unknownTier=new AiWorkflowClient(policy(10,100000,mixed),() -> rejected,() -> provider,e -> {});
        unknownTier.complete(request("Identify","Reference"),deadline());assertNull(unknownTier.snapshot().get("estimatedCostUsd"));assertEquals(false,unknownTier.snapshot().get("costRatesConfigured"));
    }
}
