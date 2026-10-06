package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** One attempt-wide budget and quality pipeline; only read-only discovery can reuse or escalate. */
public final class AiWorkflowClient implements ModelClient {
    private static final Set<String> READ_ONLY=JsonModelClient.DISCOVERY;
    private static final Set<String> REUSABLE=Set.of("map","heal","case-target","case-target-batch","discover-routes");
    private final FrameworkConfig.Agent policy;
    private final Supplier<ModelClient> primary,fallback;
    private final Consumer<Map<String,Object>> events;
    private final Map<String,Response> cache=new LinkedHashMap<>();
    private final String workflowId=UUID.randomUUID().toString();
    private int calls,escalations,reuses,unknownUsage;
    private long chargedTokens;
    private long inputBytesSent,compactBytesSaved,outputTokensReserved;
    private BigDecimal estimatedCost=BigDecimal.ZERO;
    private boolean stopped;
    private boolean costKnown;

    public AiWorkflowClient(FrameworkConfig.Agent policy,Supplier<ModelClient> primary,Supplier<ModelClient> fallback,Consumer<Map<String,Object>> events) {
        this.policy=policy;this.primary=primary;this.fallback=fallback;this.events=events;policy.workflow().validate();costKnown=policy.workflow().inputUsdPerMillionTokens()!=null;
    }
    public static ModelClient create(FrameworkConfig.Agent policy,SecretRegistry secrets,Consumer<Map<String,Object>> events) {
        var first=new JsonModelClient(policy,secrets);ModelClient[] stronger={null};
        return new AiWorkflowClient(policy,() -> first,() -> {
            if(stronger[0]==null) stronger[0]=new JsonModelClient(tier(policy),secrets);return stronger[0];
        },events);
    }
    private static FrameworkConfig.Agent tier(FrameworkConfig.Agent policy) {
        var t=policy.workflow().escalation();
        return new FrameworkConfig.Agent(t.endpoint(),t.model(),t.apiKeyRef(),policy.maxActions(),policy.maxModelCalls(),policy.maxTokens(),policy.actTimeoutSeconds(),policy.workflow());
    }
    public String identity() { return "cost-quality-json-v1"; }
    public Response complete(Request request,Deadline deadline) {
        deadline.check();Failure.require(!stopped,"AI_BUDGET_EXHAUSTED","AI workflow was stopped after a budget or accounting violation");
        boolean readOnly=READ_ONLY.contains(request.kind());
        if(readOnly && request.observation()!=null) Failure.require(request.observation().complete(),"LOCATOR_INCOMPLETE","AI workflow cannot use incomplete UI evidence");
        String key=key(request);boolean reusable=policy.workflow().reuseReadOnlyResponses() && REUSABLE.contains(request.kind());
        if(reusable && cache.containsKey(key)) {
            reuses++;events.accept(Map.of("kind","ai-response-reused","request",request.kind()));summary();return usage(cache.get(key),0L);
        }
        long before=chargedTokens;Request current=request;Response response;
        try { response=invoke(current,false,deadline);validate(current,response,readOnly); }
        catch(Failure failure) {
            boolean limited=failure.code().equals("MODEL_OUTPUT_LIMIT");
            if(!readOnly || !limited && !failure.code().equals("MODEL_OUTPUT_INVALID")) throw failure;
            events.accept(Map.of("kind","ai-quality-rejected","request",request.kind(),"code",failure.code()));
            if(limited && JsonModelClient.outputLimit(policy,current)>=policy.workflow().maxOutputTokens()) throw rejected();
            boolean escalate=!limited && policy.workflow().escalation()!=null && escalations<policy.workflow().maxEscalations();
            if(!escalate && policy.workflow().maxProtocolRetries()==0) throw rejected();
            current=new Request(request.kind(),request.instruction()+" Return exactly the required finish/data JSON and schema; use only supplied current node IDs and routes.",
                request.params(),request.observation(),request.ledger(),request.schema(),true);
            if(escalate) { escalations++;events.accept(Map.of("kind","ai-escalated","request",request.kind(),"reason","MODEL_OUTPUT_INVALID")); }
            try { response=invoke(current,escalate,deadline);validate(current,response,true); }
            catch(Failure second) { if(Set.of("MODEL_OUTPUT_INVALID","MODEL_OUTPUT_LIMIT").contains(second.code())) throw rejected();throw second; }
        }
        if(reusable && "PASSED".equals(response.verdict())) {
            if(cache.size()>=256) cache.remove(cache.keySet().iterator().next());cache.put(key,copy(response));
        }
        return usage(response,chargedTokens-before);
    }
    private Failure rejected() { summary();return new Failure("AI_WORKFLOW_REJECTED","AI discovery failed its bounded quality check; refine the supplied scope/intent or evaluated model configuration"); }
    /** Optional batching must not spend or stop a workflow that can still afford a single target. */
    boolean canReserve(Request request) {
        var w=policy.workflow();
        int bytes=Json.string(JsonModelClient.body(policy,request)).getBytes(StandardCharsets.UTF_8).length;
        int output=JsonModelClient.outputLimit(policy,request);long input=(long)bytes+1024;
        return !stopped && bytes<=w.maxInputBytes() && calls<policy.maxModelCalls() && input+output<=policy.maxTokens()-chargedTokens &&
            (w.maxCostUsd().signum()==0 || estimatedCost.add(cost(input,output,w.inputUsdPerMillionTokens(),w.outputUsdPerMillionTokens())).compareTo(w.maxCostUsd())<=0);
    }
    private Response invoke(Request request,boolean stronger,Deadline deadline) {
        var workflow=policy.workflow();var selected=stronger?tier(policy):policy;
        int bytes=Json.string(JsonModelClient.body(selected,request)).getBytes(StandardCharsets.UTF_8).length;
        Failure.require(bytes<=workflow.maxInputBytes(),"AI_CONTEXT_LIMIT","AI request is too large; narrow the UI scope or supplied intent without truncating evidence");
        int outputLimit=JsonModelClient.outputLimit(selected,request);
        long inputReserve=(long)bytes+1024,reserve=inputReserve+outputLimit;
        BigDecimal inputRate=stronger?workflow.escalation().inputUsdPerMillionTokens():workflow.inputUsdPerMillionTokens();
        BigDecimal outputRate=stronger?workflow.escalation().outputUsdPerMillionTokens():workflow.outputUsdPerMillionTokens();
        BigDecimal costReserve=cost(inputReserve,outputLimit,inputRate,outputRate);
        if(calls>=policy.maxModelCalls() || reserve>policy.maxTokens()-chargedTokens ||
            workflow.maxCostUsd().signum()>0 && estimatedCost.add(costReserve).compareTo(workflow.maxCostUsd())>0) {
            stopped=true;summary();throw new Failure("AI_BUDGET_EXHAUSTED","AI workflow cannot reserve the next model call within the shared call/token/estimated-cost budget");
        }
        calls++;chargedTokens+=reserve;estimatedCost=estimatedCost.add(costReserve);
        inputBytesSent+=bytes;outputTokensReserved+=outputLimit;
        compactBytesSaved+=Math.max(0,Json.string(JsonModelClient.body(selected,request,false)).getBytes(StandardCharsets.UTF_8).length-bytes);
        if(inputRate==null) costKnown=false;
        Map<String,Object> started=new LinkedHashMap<>();started.put("kind","model-call-started");started.put("call",calls);started.put("request",request.kind());
        started.put("tier",stronger?"escalation":"primary");started.put("reservedTokens",reserve);started.put("costRatesConfigured",inputRate!=null);
        started.put("inputBytes",bytes);started.put("outputTokenLimit",outputLimit);
        if(inputRate!=null) started.put("reservedCostUsd",costReserve);
        events.accept(started);summary();
        boolean settled=false;
        try {
            Response response=(stronger?fallback:primary).get().complete(request,deadline);
            if(response!=null && response.tokens()==null && (response.inputTokens()!=null || response.outputTokens()!=null)) {
                stopped=true;throw new Failure("AI_USAGE_INVALID","Provider token breakdown requires total usage; AI workflow stopped");
            }
            if(response==null || response.tokens()==null) { unknownUsage++;settled=true; }
            else {
                long total=response.tokens();
                if(total<0 || (response.inputTokens()==null)!=(response.outputTokens()==null) || response.inputTokens()!=null &&
                    (response.inputTokens()<0 || response.outputTokens()<0 || response.inputTokens()>total || response.outputTokens()!=total-response.inputTokens())) {
                    stopped=true;throw new Failure("AI_USAGE_INVALID","Provider usage accounting is inconsistent; AI workflow stopped");
                }
                BigDecimal charge=inputRate==null?BigDecimal.ZERO:
                    response.inputTokens()!=null && response.outputTokens()!=null?cost(response.inputTokens(),response.outputTokens(),inputRate,outputRate):
                    BigDecimal.valueOf(total).multiply(inputRate.max(outputRate)).movePointLeft(6);
                try { chargedTokens=Math.addExact(chargedTokens-reserve,total); }
                catch(ArithmeticException overflow) { stopped=true;throw new Failure("AI_USAGE_INVALID","Provider usage overflow; AI workflow stopped"); }
                estimatedCost=estimatedCost.subtract(costReserve).add(charge);settled=true;
                boolean over=total>reserve || response.outputTokens()!=null && response.outputTokens()>outputLimit ||
                    chargedTokens>policy.maxTokens() || workflow.maxCostUsd().signum()>0 && estimatedCost.compareTo(workflow.maxCostUsd())>0;
                if(over) { stopped=true;throw new Failure("AI_BUDGET_EXHAUSTED","Provider usage exceeded its reservation/cap; AI workflow stopped"); }
            }
            events.accept(Map.of("kind","model-call-completed","call",calls,"request",request.kind(),"tier",stronger?"escalation":"primary"));return response;
        } catch(RuntimeException failure) {
            if(!settled) unknownUsage++;
            if(failure instanceof Failure f && f.code().equals("AI_USAGE_INVALID")) stopped=true;
            events.accept(Map.of("kind","model-call-failed","call",calls,"request",request.kind(),"code",failure instanceof Failure f?f.code():"MODEL_UNAVAILABLE"));throw failure;
        } finally { summary(); }
    }
    private static BigDecimal cost(long input,long output,BigDecimal inputRate,BigDecimal outputRate) {
        return inputRate==null?BigDecimal.ZERO:BigDecimal.valueOf(input).multiply(inputRate).add(BigDecimal.valueOf(output).multiply(outputRate)).movePointLeft(6);
    }
    private static void validate(Request request,Response response,boolean readOnly) {
        Failure.require(response!=null,"MODEL_OUTPUT_INVALID","Empty AI response");
        if(!readOnly) return;
        Failure.require("finish".equals(response.tool()) && response.nodeId()==null && response.paramRef()==null && response.value()==null,
            "MODEL_OUTPUT_INVALID","Discovery must return read-only structured output");
        if(Set.of("FAILED","BLOCKED").contains(Objects.toString(response.verdict(),""))) {
            Failure.require(response.errorCode()!=null && !response.errorCode().isBlank(),"MODEL_OUTPUT_INVALID","A blocked AI response requires a diagnostic code");return;
        }
        Failure.require("PASSED".equals(response.verdict()) && response.errorCode()==null,"MODEL_OUTPUT_INVALID","Invalid AI discovery verdict");
        JsonSchema.validate(response.data(),request.schema());
        if(request.observation()!=null) {
            Set<String> available=new HashSet<>();request.observation().nodes().forEach(n -> available.add(n.id()));
            if(response.data().has("nodeId")) Failure.require(available.contains(response.data().get("nodeId").asText()),"MODEL_OUTPUT_INVALID","AI referenced an unavailable node");
            if(request.kind().equals("map")) {
                Set<String> seen=new HashSet<>(),keys=new HashSet<>();
                for(var target:response.data().path("targets")) Failure.require(available.contains(target.path("nodeId").asText()) &&
                    seen.add(target.path("nodeId").asText()) && keys.add(target.path("key").asText()) && !target.path("key").asText().startsWith("records."),"MODEL_OUTPUT_INVALID","AI mapping is incomplete, duplicate or reserved");
                Failure.require(seen.equals(available),"MODEL_OUTPUT_INVALID","AI mapping omitted supplied controls");
            }
        }
        if(request.kind().equals("case-target-batch")) {
            var manifest=Json.MAPPER.valueToTree(request.params().get("targets"));Map<String,com.fasterxml.jackson.databind.JsonNode> intents=new HashMap<>();
            for(var target:manifest) intents.put(target.path("id").asText(),target);
            Set<String> seen=new HashSet<>();
            for(var target:response.data().path("targets")) {
                String id=target.path("id").asText();Failure.require(intents.containsKey(id) && seen.add(id),"MODEL_OUTPUT_INVALID","Invalid or duplicate batch intent");
                if(!target.path("nodeId").isNull()) {
                    var node=request.observation().nodes().stream().filter(n -> n.id().equals(target.path("nodeId").asText())).findFirst();
                    Failure.require(node.isPresent(),"MODEL_OUTPUT_INVALID","Batch referenced an unavailable node");
                    for(var kind:intents.get(id).path("kinds")) Failure.require(CaseLearner.permittedRole(kind.asText(),node.get().target().role()),"MODEL_OUTPUT_INVALID","Batch target has the wrong action role");
                }
            }
            Failure.require(seen.equals(intents.keySet()),"MODEL_OUTPUT_INVALID","Batch omitted supplied intent");
        }
        if(request.kind().equals("discover-routes")) {
            var candidates=Json.MAPPER.valueToTree(request.params().get("candidates"));Set<String> allowed=new HashSet<>();
            for(var candidate:candidates) allowed.add(candidate.isTextual()?candidate.asText():candidate.path("route").asText());
            for(var route:response.data().path("routes")) Failure.require(allowed.contains(route.asText()),"MODEL_OUTPUT_INVALID","AI invented a navigation route");
        }
    }
    private static String key(Request request) {
        var tree=Json.MAPPER.valueToTree(request);
        if(tree.path("observation").isObject()) { var observation=(com.fasterxml.jackson.databind.node.ObjectNode)tree.get("observation");observation.remove(List.of("revision","capturedAt")); }
        return Json.hash(tree);
    }
    private static Response copy(Response response) { return Json.MAPPER.convertValue(Json.MAPPER.valueToTree(response),Response.class); }
    private static Response usage(Response response,long tokens) {
        return new Response(response.tool(),response.nodeId(),response.paramRef(),response.value(),response.verdict(),response.reason(),response.errorCode(),
            response.evidenceRefs(),response.data()==null?null:response.data().deepCopy(),tokens,null,null);
    }
    public Map<String,Object> snapshot() {
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("providerCalls",calls);snapshot.put("escalations",escalations);snapshot.put("reusedResponses",reuses);
        snapshot.put("chargedTokens",chargedTokens);snapshot.put("unknownUsageCalls",unknownUsage);snapshot.put("estimatedCostUsd",costKnown?estimatedCost:null);
        snapshot.put("inputBytesSent",inputBytesSent);snapshot.put("compactBytesSaved",compactBytesSaved);snapshot.put("outputTokensReserved",outputTokensReserved);
        snapshot.put("costRatesConfigured",costKnown);snapshot.put("stopped",stopped);return Collections.unmodifiableMap(snapshot);
    }
    private void summary() { events.accept(Map.of("kind","ai-workflow-summary","workflowId",workflowId,"metrics",snapshot())); }
}
