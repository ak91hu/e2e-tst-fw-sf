package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.replay.CacheStore;
import tools.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

public final class SfAgent {
    public static final class ActResult {
        public final String stepId, key, recordId;
        public final List<CacheStore.Action> actions;
        public final Map<String,StepParam> params;
        public final ActScope scope;
        public final String cacheStatus;
        public boolean confirmed;
        public final int modelCalls;
        public final Long tokens;
        private ActResult(String step,String key,ActScope scope,List<CacheStore.Action> actions,Map<String,StepParam> params,String status,Budget budget) {
            this.stepId=step;this.key=key;this.scope=scope;this.recordId=scope.recordId();this.actions=List.copyOf(actions);this.params=Map.copyOf(params);this.cacheStatus=status;this.modelCalls=budget.calls();this.tokens=budget.tokens();
        }
    }
    private final UiEngine engine;
    private final StepExecutor executor;
    private final FrameworkConfig config;
    private final Persona persona;
    private final String caseId,attemptId;
    private final Deadline attempt;
    private final CacheStore store;
    private final Set<String> steps=new HashSet<>();
    private final List<ActResult> results=new ArrayList<>();
    private final java.util.function.Consumer<Map<String,Object>> event;
    public SfAgent(UiEngine engine,Supplier<ModelClient> provider,FrameworkConfig config,Persona persona,String caseId,String attemptId,Deadline attempt,java.util.function.Consumer<Map<String,Object>> event) {
        this(engine,new ModelStepExecutor(provider),config,persona,caseId,attemptId,attempt,event);
    }
    public SfAgent(UiEngine engine,StepExecutor executor,FrameworkConfig config,Persona persona,String caseId,String attemptId,Deadline attempt,java.util.function.Consumer<Map<String,Object>> event) {
        this.engine=engine;this.executor=executor;this.config=config;this.persona=persona;this.caseId=caseId;this.attemptId=attemptId;this.attempt=attempt;this.event=event;
        store=new CacheStore(Path.of(config.cache().dir()));
    }
    private Map<String,Object> values(Map<String,StepParam> params) { Map<String,Object> values=new TreeMap<>();params.forEach((k,v)->values.put(k,v.value()));return values; }
    public ActResult act(String stepId,String instruction,Map<String,StepParam> params,ActScope scope) {
        SafePaths.segment(stepId);
        Failure.require(steps.add(stepId),"MODEL_OUTPUT_INVALID","Duplicate step ID");
        Failure.require(attemptId.equals(scope.attemptId()),"RECORD_SCOPE_VIOLATION","Scope belongs to a different attempt");
        Deadline deadline=attempt.child(Duration.ofSeconds(config.agent().actTimeoutSeconds()));
        Budget budget=new Budget(config.agent().maxActions(),config.agent().maxModelCalls(),config.agent().maxTokens());
        Map<String,Object> keyParams=new TreeMap<>();
        params.forEach((k,v)->keyParams.put(k,v.unique()?Map.of("slot",k,"type",v.value().getClass().getSimpleName()):v.value()));
        boolean collision=false;
        for(var a:params.entrySet()) if(a.getValue().unique()) for(var b:params.entrySet())
            if(!a.getKey().equals(b.getKey()) && (String.valueOf(a.getValue().value()).contains(String.valueOf(b.getValue().value())) || String.valueOf(b.getValue().value()).contains(String.valueOf(a.getValue().value())))) collision=true;
        String route=engine.route();
        String normalized=route.replace("/"+scope.recordId()+"/","/{record}/");
        String key=Json.hash(Map.ofEntries(Map.entry("schema",1),Map.entry("algorithm",1),Map.entry("project",config.projectId()),
            Map.entry("case",caseId),Map.entry("step",stepId),Map.entry("instruction",instruction),Map.entry("params",keyParams),
            Map.entry("org",config.org()),Map.entry("ui",config.ui()),Map.entry("persona",persona),Map.entry("personaProfile",config.persona(persona)),
            Map.entry("route",normalized),Map.entry("object",scope.objectType()),Map.entry("fields",scope.fields().stream().sorted().toList()),
            Map.entry("policy","unsaved-fields-v1"),Map.entry("engine",engine.capabilityVersion()),Map.entry("executor",executor.identity())));
        List<CacheStore.Action> actions=new ArrayList<>();
        List<Map<String,Object>> ledger=new ArrayList<>();
        Observation baseline=engine.observe(scope.container(),deadline);
        String status=config.cache().mode().equals("off")?"OFF":"MISS";
        if(!config.cache().mode().equals("off")) {
            Optional<CacheStore.Trace> trace;
            try { trace=store.get(key); }
            catch(Failure e) {
                if(config.cache().strict() || !e.code().equals("CACHE_STORE_FAILED") || config.cache().onStoreFailure().equals("fail")) throw e;
                trace=Optional.empty();
            }
            if(trace.isPresent()) {
                try {
                    for(var action:trace.get().actions()) {
                        var observation=engine.observe(scope.container(),deadline);
                        var node=engine.resolve(action.target(),observation);
                        String value=action.paramRef()!=null?String.valueOf(requiredParam(params,action.paramRef()).value()):action.literal();
                        scope.validate(observation,node,action.kind(),value,params);
                        budget.action(); engine.perform(new UiEngine.Action(action.kind(),node.id(),value),observation,deadline);
                        actions.add(action); ledger.add(Map.of("kind",action.kind(),"target",action.target(),"value",value));
                    }
                    validateEnd(actions,params,baseline,scope,deadline);
                    return result(stepId,key,scope,actions,params,"REPLAYED",budget);
                } catch(Failure e) {
                    if(config.cache().strict() || !Set.of("TARGET_NOT_FOUND","TARGET_AMBIGUOUS","NODE_STALE","END_MISMATCH").contains(e.code()))
                        throw new Failure("REPLAY_STALE","Replay stopped: "+e.code());
                    status="HANDOFF"; ledger.add(Map.of("handoffReason",e.code()));
                }
            } else Failure.require(!config.cache().requireRecorded(),"REPLAY_STALE","Required recording missing");
        }
        int repairs=0,consecutiveFailures=0; Map<String,Integer> repetitions=new HashMap<>();
        while(true) {
            deadline.check(); budget.call();
            Observation observation=engine.observe(scope.container(),deadline);
            ModelClient.Response response;
            try {
                event.accept(Map.of("kind","model-call","stepId",stepId,"call",budget.calls(),"usage","pending"));
                response=executor.next(new ModelClient.Request("act",instruction,values(params),observation,List.copyOf(ledger),null),deadline);
                budget.usage(response.tokens()); validateResponse(response,observation);
                event.accept(Map.of("kind","model-usage","stepId",stepId,"call",budget.calls(),"tokens",response.tokens()==null?"unknown":response.tokens()));
            } catch(Failure e) {
                budget.usage(null);
                if(e.code().equals("MODEL_OUTPUT_INVALID") && repairs++==0) { ledger.add(Map.of("repair","Return one valid response matching the protocol")); continue; }
                throw e;
            }
            deadline.check();
            if(response.tool().equals("finish")) {
                Failure.require("PASSED".equals(response.verdict()),response.errorCode()==null?"UI_ASSERTION_FAILED":response.errorCode(),response.reason());
                validateEnd(actions,params,baseline,scope,deadline);
                return result(stepId,key,scope,actions,params,collision?"NOT_RECORDED":status,budget);
            }
            var node=observation.nodes().stream().filter(n -> n.id().equals(response.nodeId())).findFirst()
                .orElseThrow(() -> new Failure("NODE_STALE","Model referenced an unknown node"));
            String value=response.paramRef()!=null?String.valueOf(requiredParam(params,response.paramRef()).value()):response.value();
            scope.validate(observation,node,response.tool(),value,params);
            String repeat=Json.hash(Map.of("target",node.target(),"kind",response.tool(),"value",value==null?"":value));
            Failure.require(repetitions.merge(repeat,1,Integer::sum)<=3 && consecutiveFailures<5,"AGENT_LOOP_DETECTED","Repeated action loop");
            budget.action();
            try {
                engine.perform(new UiEngine.Action(response.tool(),node.id(),value),observation,deadline);
                actions.add(new CacheStore.Action(response.tool(),node.target(),response.paramRef(),response.paramRef()==null?value:null));
                ledger.add(Map.of("kind",response.tool(),"target",node.target(),"value",value==null?"":value)); consecutiveFailures=0;
            } catch(Failure e) {
                if(Set.of("POLICY_DENIED","RECORD_SCOPE_VIOLATION","STEP_TIMEOUT").contains(e.code())) throw e;
                consecutiveFailures++; ledger.add(Map.of("actionFailure",e.code()));
            }
        }
    }
    private StepParam requiredParam(Map<String,StepParam> params,String name) {
        var value=params.get(name); Failure.require(value!=null,"MODEL_OUTPUT_INVALID","Unknown parameter reference"); return value;
    }
    private void validateResponse(ModelClient.Response r,Observation observation) {
        Failure.require(r!=null && r.tool()!=null,"MODEL_OUTPUT_INVALID","Missing response tool");
        if(r.tool().equals("finish")) {
            Failure.require(Set.of("PASSED","FAILED","BLOCKED").contains(r.verdict()==null?"":r.verdict()) && r.reason()!=null && !r.reason().isBlank() &&
                r.evidenceRefs()!=null && !r.evidenceRefs().isEmpty() && r.evidenceRefs().stream().allMatch(id -> observation.nodes().stream().anyMatch(n -> n.id().equals(id))) &&
                ("PASSED".equals(r.verdict()) ? r.errorCode()==null : r.errorCode()!=null) && r.nodeId()==null && r.paramRef()==null && r.value()==null,
                "MODEL_OUTPUT_INVALID","Invalid terminal verdict or evidence references");
        } else Failure.require(Set.of("fill","select","setChecked","press","scroll").contains(r.tool()) && r.nodeId()!=null && r.verdict()==null &&
            (r.paramRef()!=null ^ r.value()!=null),"MODEL_OUTPUT_INVALID","Invalid action response");
    }
    private void validateEnd(List<CacheStore.Action> actions,Map<String,StepParam> params,Observation baseline,ActScope scope,Deadline deadline) {
        Failure.require(!actions.isEmpty(),"END_MISMATCH","No-op acts cannot be recorded");
        Observation end=engine.observe(scope.container(),deadline);
        Failure.require(end.complete(),"END_MISMATCH","Incomplete end observation");
        boolean changed=false;
        Map<SemanticTarget,CacheStore.Action> finalActions=new LinkedHashMap<>();
        for(var action:actions) if(Set.of("fill","select","setChecked").contains(action.kind())) finalActions.put(action.target(),action);
        Failure.require(!finalActions.isEmpty(),"END_MISMATCH","Act has no verifiable field mutation");
        for(var action:finalActions.values()) {
            var node=engine.resolve(action.target(),end);
            String wanted=action.paramRef()!=null?String.valueOf(requiredParam(params,action.paramRef()).value()):action.literal();
            String actual=action.kind().equals("setChecked")?String.valueOf(node.checked()):node.value().isEmpty() && action.kind().equals("select")?node.text():node.value();
            boolean matches=Objects.equals(actual,wanted);
            if(!matches && wanted.matches("-?[0-9]+(?:\\.[0-9]+)?")) try { matches=UiMoneyParser.money(actual).compareTo(new java.math.BigDecimal(wanted))==0; } catch(Failure ignored) {}
            Failure.require(matches,"END_MISMATCH","Current unsaved field does not match declared value: "+action.target());
            changed |= baseline.nodes().stream().filter(n -> n.target().equals(node.target())).anyMatch(n -> !Objects.equals(n.value(),node.value()) || !Objects.equals(n.text(),node.text()) || n.checked()!=node.checked());
        }
        Failure.require(changed,"END_MISMATCH","No current UI delta proves the act");
    }
    private ActResult result(String step,String key,ActScope scope,List<CacheStore.Action> actions,Map<String,StepParam> params,String status,Budget budget) {
        var result=new ActResult(step,key,scope,actions,params,status,budget);results.add(result);
        event.accept(Map.of("stepId",step,"kind","act","cacheStatus",status,"modelCalls",budget.calls(),"actions",budget.actions(),"tokens",budget.tokens()==null?"unknown":budget.tokens(),"proof","PENDING"));return result;
    }
    public void confirm(ActResult result,UiVerification proof) {
        Failure.require(results.contains(result) && !result.confirmed && attemptId.equals(proof.attemptId()) && result.recordId.equals(proof.recordId()),
            "PROOF_MISSING","Proof does not belong to this act and attempt");
        Map<SemanticTarget,CacheStore.Action> finalActions=new LinkedHashMap<>();
        for(var action:result.actions) if(Set.of("fill","select","setChecked").contains(action.kind())) finalActions.put(action.target(),action);
        for(var action:finalActions.values()) {
            String label=action.target().label()!=null?action.target().label():action.target().name();
            Object expected=proof.expected().get(label);
            String value=action.paramRef()!=null?String.valueOf(result.params.get(action.paramRef()).value()):action.literal();
            Failure.require(expected!=null && String.valueOf(expected).equals(value),"PROOF_MISSING","Proof must cover each final mutated field exactly");
        }
        result.confirmed=true;
        if(config.cache().mode().equals("read-write") && !result.cacheStatus.equals("NOT_RECORDED")) store.put(result.key,result.actions);
        event.accept(Map.of("stepId",result.stepId,"proofId",proof.id(),"proof","CONFIRMED"));
    }
    public void requireProofs() {
        for(var result:results) Failure.require(result.confirmed,"PROOF_MISSING","Agent act lacks persisted UI proof: "+result.stepId);
    }
    public void invalidatePending() {
        if(config.cache().mode().equals("read-write")) for(var result:results) if(!result.confirmed) store.invalidate(result.key);
    }
    public void assertThat(String instruction) { judge("assert",instruction,null); }
    public JsonNode extract(String instruction,JsonNode schema) { return judge("extract",instruction,schema).data(); }
    private ModelClient.Response judge(String kind,String instruction,JsonNode schema) {
        Deadline deadline=attempt.child(Duration.ofSeconds(90)); Budget budget=new Budget(1,2,config.agent().maxTokens());
        ModelClient.Response response=null;
        for(int repair=0;repair<2;repair++) {
            Observation observation=engine.observe("page",deadline);
            Failure.require(observation.complete(),kind.equals("extract")?"EXTRACTION_INCONCLUSIVE":"ASSERTION_INCONCLUSIVE","Observation is incomplete");
            budget.call();event.accept(Map.of("kind","model-call","stepKind",kind,"call",budget.calls(),"usage","pending"));
            response=executor.next(new ModelClient.Request(kind,instruction,Map.of(),observation,List.of(),schema),deadline);
            budget.usage(response.tokens());deadline.check();
            try { validateResponse(response,observation);
                Failure.require(response.tool().equals("finish"),"MODEL_OUTPUT_INVALID","Read-only judge cannot request UI actions");
                if(kind.equals("extract") && "PASSED".equals(response.verdict())) validateData(response.data(),schema);
                break;
            } catch(Failure e) { if(repair==1 || !e.code().equals("MODEL_OUTPUT_INVALID")) throw e; }
        }
        Failure.require(response!=null && "PASSED".equals(response.verdict()),response==null?"MODEL_OUTPUT_INVALID":response.errorCode()==null?"UI_ASSERTION_FAILED":response.errorCode(),response==null?"No judge response":response.reason());
        if(kind.equals("extract")) validateData(response.data(),schema);
        event.accept(Map.of("kind",kind,"verdict",response.verdict(),"modelCalls",budget.calls(),"tokens",budget.tokens()==null?"unknown":budget.tokens()));
        return response;
    }
    private void validateData(JsonNode data,JsonNode schema) {
        JsonSchema.validate(data,schema);
    }
    public void waitFor(String instruction,Duration timeout) {
        Deadline deadline=attempt.child(timeout); String previous=null; int calls=0;
        while(true) {
            deadline.check(); Observation observation=engine.observe("page",deadline);
            Failure.require(observation.complete(),"ASSERTION_INCONCLUSIVE","Wait observation incomplete");
            String digest=Json.hash(observation.nodes().stream().map(n -> List.of(n.target(),n.text(),n.value(),n.checked())).toList());
            if(!digest.equals(previous)) {
                Failure.require(calls++<10,"STEP_BUDGET_EXHAUSTED","Wait judge call limit reached");
                event.accept(Map.of("kind","model-call","stepKind","wait","call",calls,"usage","pending"));
                var response=executor.next(new ModelClient.Request("wait",instruction,Map.of(),observation,List.of(),null),deadline);
                validateResponse(response,observation); Failure.require(response.tool().equals("finish"),"MODEL_OUTPUT_INVALID","Wait is read-only");
                if("PASSED".equals(response.verdict())) return;
                if("BLOCKED".equals(response.verdict())) throw new Failure(response.errorCode(),response.reason());
                previous=digest;
            }
            engine.poll(deadline);
        }
    }
}
