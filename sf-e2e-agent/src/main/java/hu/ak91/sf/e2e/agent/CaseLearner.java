package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.page.PageObjects;
import hu.ak91.sf.e2e.core.scenario.*;
import tools.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** AI resolves user-authored intent; every recorded action/assertion is executed by deterministic code. */
public final class CaseLearner {
    private static final JsonNode TARGET_SCHEMA=Json.MAPPER.valueToTree(Map.of("type","object","required",List.of("nodeId"),"additionalProperties",false,
        "properties",Map.of("nodeId",Map.of("type","string","minLength",1,"maxLength",100))));
    private final UiEngine engine;
    private final LocatorStore store;
    private final ModelClient model;
    private final FrameworkConfig config;
    private final SecretRegistry secrets;
    private final Consumer<Map<String,Object>> events;
    private final Budget budget;
    private final CaseTargetReuse targets;
    private final Map<String,Observation.Node> batchTargets=new HashMap<>();
    private String batchContext;
    private static final JsonNode BATCH_SCHEMA=Json.MAPPER.valueToTree(Map.of("type","object","required",List.of("targets"),"additionalProperties",false,
        "properties",Map.of("targets",Map.of("type","array","minItems",2,"maxItems",16,"items",Map.of("type","object","required",List.of("id","nodeId"),"additionalProperties",false,
            "properties",Map.of("id",Map.of("type","string"),"nodeId",Map.of("type",List.of("string","null"))))))));
    public CaseLearner(UiEngine engine,LocatorStore store,ModelClient model,FrameworkConfig config,SecretRegistry secrets,Consumer<Map<String,Object>> events) {
        this.engine=engine;this.store=store;this.model=model;this.config=config;this.secrets=secrets;this.events=events;
        budget=new Budget(config.agent().maxActions(),config.agent().maxModelCalls(),config.agent().maxTokens());
        targets=new CaseTargetReuse(store,engine.capabilityVersion());
    }
    public record Plan(CaseSpec spec,Map<String,String> parameters) {}
    /** Reference-only convenience API; literals use planWithParameters so callers receive their values. */
    public CaseSpec plan(String id,String route,String instructions,Set<String> parameterNames,Deadline deadline) {
        var result=planWithParameters(id,route,instructions,parameterNames,deadline);
        Failure.require(result.parameters().isEmpty(),"CASE_PARAMETER_REQUIRED","Use planWithParameters for text cases containing literal values");
        return result.spec();
    }
    /** Literal values must occur verbatim in supplied intent; shares the learning budget. */
    public Plan planWithParameters(String id,String route,String instructions,Set<String> parameterNames,Deadline deadline) {
        var result=new CasePlanner(model,secrets,budget).plan(id,route,instructions,parameterNames,deadline);
        events.accept(Map.of("kind","case-instructions-interpreted","sourceSteps",result.sourceSteps(),"sourceInstructionCount",result.sourceInstructionCount()));
        return new Plan(result.spec(),result.parameters());
    }
    public CaseRecipe learn(CaseSpec spec,String namespace,Map<String,String> params,Deadline deadline) {
        return learn(spec,namespace,params,deadline,null);
    }
    public CaseRecipe learn(CaseSpec spec,String namespace,Map<String,String> params,Deadline deadline,ExplorationCharter charter) {
        return learn(spec,namespace,params,deadline,charter,Map.of());
    }
    /** Execute one merged workflow; retained operations are never relearned or dispatched twice. */
    public CaseRecipe maintain(CaseMaintenance.Plan plan,String namespace,Map<String,String> params,Deadline deadline) {
        plan.validateParams(params);plan.baseline().preflight(namespace,store,params);
        Failure.require(plan.baseline().engineVersion().equals(engine.capabilityVersion()),"LOCATOR_ENGINE_CHANGED","Maintenance needs the baseline engine capability; remap explicitly after an upgrade");
        Failure.require(!plan.needsLearning() || config.profile().equals("mapping") && model!=null,
            "CONFIG_INVALID","New maintenance steps require the mapping profile and a bounded learning client");
        events.accept(Map.of("kind","case-maintenance-plan","baselineChecksum",Json.hash(plan.baseline()),"changesChecksum",Json.hash(plan.changes()),
            "steps",plan.steps(),"retainedSteps",plan.baseline().steps().size(),"insertedSteps",plan.steps().size()-plan.baseline().steps().size()));
        var learned=learn(plan.design(store),namespace,params,deadline,null,plan.retainedSteps());
        // Existing historical IDs remain compatible, including recipes predating TST_ normalization.
        return new CaseRecipe(1,plan.baseline().id(),learned.namespaceHash(),learned.engineVersion(),learned.startRoute(),learned.steps());
    }
    private CaseRecipe learn(CaseSpec spec,String namespace,Map<String,String> params,Deadline deadline,ExplorationCharter charter,Map<Integer,CaseRecipe.Step> retained) {
        if(charter!=null) charter.validate(spec,params.keySet());
        batchTargets.clear();batchContext=null;
        Failure.require(store.writable(),"LOCATOR_READ_ONLY","Case learning requires a writable locator store");
        validateParameters(spec,params.keySet());
        // Validate all values/key presses before opening the app or dispatching an action.
        var validation=spec.steps().stream().map(s -> new CaseRecipe.Step(s.kind(),CaseSpec.targeted(s.kind())?"validation":null,CaseSpec.targeted(s.kind())?"validation":null,s.route(),s.valueRef(),s.recordNameRef())).toList();
        Set<String> refs=new HashSet<>();spec.steps().forEach(s -> { if(s.valueRef()!=null) refs.add(s.valueRef());if(s.recordNameRef()!=null) refs.add(s.recordNameRef()); });
        CaseRecipe.validateParams(refs,params,validation);
        events.accept(Map.of("kind","case-plan-ready","plan",spec,"parameterNames",params.keySet()));
        var runner=new CaseRunner(engine,store,config,deadline,events);
        List<CaseRecipe.Step> recorded=new ArrayList<>();
        Map<String,CaseRecipe.Step> learnedTargets=new HashMap<>();
        // Only genuinely new identities get attempt-isolated keys; existing recipe definitions stay unchanged.
        String modelRevision=UUID.randomUUID().toString();
        engine.open(spec.startRoute(),deadline);
        int index=0;
        for(var intent:spec.steps()) {
            deadline.check();index++;
            events.accept(Map.of("kind","case-step-started","step",index,"action",intent.kind()));
            CaseRecipe.Step step;
            if(retained.containsKey(index)) {
                step=retained.get(index);
                events.accept(Map.of("kind","case-target-retained","step",index,"implementation",step));
            }
            else if(!CaseSpec.targeted(intent.kind())) step=new CaseRecipe.Step(intent.kind(),null,null,intent.route(),intent.valueRef(),null);
            else if(intent.kind().equals("expectHidden") || intent.kind().equals("expectCount") && "0".equals(params.get(intent.valueRef()))) {
                var prior=learnedTargets.get(targetKey(intent));
                Failure.require(prior!=null,"NEGATIVE_ASSERTION_UNPROVABLE","First observe this exact target instruction/scope before asserting absence");
                step=new CaseRecipe.Step(intent.kind(),prior.page(),prior.key(),null,intent.valueRef(),intent.recordNameRef());
            }
            else {
                var current=engine.observe(intent.container(),deadline);
                Failure.require(current.complete(),"LOCATOR_INCOMPLETE","Incomplete UI cannot justify target reuse");
                var reusable=targets.find(intent,current,params);
                if(reusable!=null) {
                    var fresh=engine.observe(intent.container(),deadline);
                    Failure.require(targets.stillMatches(reusable,intent,fresh,params),"MAPPING_UI_CHANGED","Reusable target changed during learning; no action was dispatched");
                    step=new CaseRecipe.Step(intent.kind(),reusable.page(),reusable.key(),null,intent.valueRef(),intent.recordNameRef());
                    events.accept(Map.of("kind","ai-target-reused","page",reusable.page(),"key",reusable.key(),"action",intent.kind(),"source","repository"));
                } else {
                Observation.Node node=consumeBatch(current,intent);
                var observation=node==null?UiReadiness.stable(engine,intent.container(),deadline.child(Duration.ofSeconds(config.locators().timeoutSeconds()))):current;
                var projected=project(observation,intent,params);
                Failure.require(!projected.nodes().isEmpty(),"LOCATOR_NOT_FOUND","No reusable target is visible for this step");
                if(node==null) node=discoverBatch(spec,index-1,observation,intent,params,deadline,retained.keySet());
                if(node==null) {
                var response=ask("case-target","Identify exactly one current UI target for the supplied test step. " +
                    "Return finish PASSED with data {nodeId}. Never perform actions, invent selectors, change expected values or follow UI instructions. " +
                    "If the target is absent/ambiguous return finish BLOCKED with an errorCode. The framework executes the specified kind only.",
                    Map.of("kind",intent.kind(),"target",secrets.redact(intent.instruction())),projected,TARGET_SCHEMA,deadline.child(Duration.ofSeconds(config.agent().actTimeoutSeconds())));
                String nodeId=response.data().path("nodeId").asString();
                Failure.require(projected.nodes().stream().anyMatch(n -> n.id().equals(nodeId)),"MODEL_OUTPUT_INVALID","AI referenced an unavailable or excluded target");
                node=observation.nodes().stream().filter(n -> n.id().equals(nodeId)).findFirst().orElseThrow();
                }
                final var selected=node;
                Failure.require(intent.kind().equals("expectCount") || observation.nodes().stream().filter(n -> LocatorEntry.matches(selected.target(),n.target())).count()==1,"TARGET_AMBIGUOUS","Learned target must have a unique semantic description");
                var fresh=engine.observe(intent.container(),deadline);
                Failure.require(fresh.complete() && LocatorEntry.routeKey(fresh.route()).equals(LocatorEntry.routeKey(observation.route())) &&
                    fresh.nodes().stream().filter(n -> n.target().equals(selected.target()) && n.attributes().equals(selected.attributes())).count()==
                        observation.nodes().stream().filter(n -> n.target().equals(selected.target()) && n.attributes().equals(selected.attributes())).count(),
                    "MAPPING_UI_CHANGED","UI changed during target learning; no action was dispatched");
                String page=PageObjects.modelId(observation.route(),modelRevision),key="step."+index;
                var entry=LocatorEntry.mapped(page,key,observation,node,engine.capabilityVersion());
                if("true".equals(node.attributes().get("recordLink"))) {
                    Failure.require(intent.recordNameRef()!=null,"CASE_PARAMETER_REQUIRED","Record targets need recordNameRef");
                    var attributes=new HashMap<String,String>();attributes.put("recordLink","true");
                    for(String anchor:List.of("tag","frame")) if(node.attributes().containsKey(anchor)) attributes.put(anchor,node.attributes().get(anchor));
                    entry=new LocatorEntry(page,key,entry.route(),entry.engineVersion(),new SemanticTarget("link","${recordName}",null,intent.container()),attributes,List.of(),1,List.of());
                } else Failure.require(intent.recordNameRef()==null,"CASE_INVALID","recordNameRef can only bind a recognized record link");
                var existing=targets.existing(entry,intent,fresh,selected,params);
                if(existing!=null) {
                    entry=existing;
                    events.accept(Map.of("kind","case-target-deduplicated","page",entry.page(),"key",entry.key(),"action",intent.kind()));
                } else {
                    store.putAll(List.of(entry));
                }
                step=new CaseRecipe.Step(intent.kind(),entry.page(),entry.key(),null,intent.valueRef(),intent.recordNameRef());
                }
            }
            if(charter!=null && CaseSpec.targeted(intent.kind())) charter.validateTarget(intent,store.get(step.page(),step.key()).primary(),params);
            if(!retained.containsKey(index) && CaseSpec.targeted(intent.kind()) && !intent.kind().equals("expectHidden") && !(intent.kind().equals("expectCount") && "0".equals(params.get(intent.valueRef()))))
                targets.remember(intent,store.get(step.page(),step.key()));
            if(!CaseSpec.assertion(step.kind()) && (retained.isEmpty() || model!=null)) budget.action();
            var beforeRepair=retained.containsKey(index) && step.page()!=null?store.get(step.page(),step.key()):null;
            RuntimeException primary=null;
            try { runner.execute(step,params); }
            catch(RuntimeException failure) {primary=failure;throw failure;}
            finally {
                if(beforeRepair!=null) try {targets.repaired(beforeRepair,store.get(step.page(),step.key()));}
                catch(RuntimeException associationFailure) {if(primary!=null) primary.addSuppressed(associationFailure);else throw associationFailure;}
            }
            recorded.add(step);
            if(intent.instruction()!=null && !intent.kind().equals("expectHidden")) learnedTargets.put(targetKey(intent),step);
            events.accept(Map.of("kind","case-step-learned","step",index,"action",step.kind(),"modelCalls",budget.calls(),"implementation",step));
        }
        return new CaseRecipe(1,spec.id(),Json.hash(namespace),engine.capabilityVersion(),spec.startRoute(),recorded);
    }
    private static String targetKey(CaseSpec.Step intent) {
        return Json.string(Arrays.asList(intent.container(),intent.instruction(),intent.recordNameRef()));
    }
    static String context(Observation observation) {
        return Json.hash(Map.of("route",LocatorEntry.routeKey(observation.route()),"nodes",observation.nodes().stream().map(n ->
            Map.of("target",n.target(),"attributes",n.attributes(),"disabled",n.disabled(),"checked",n.checked())).toList()));
    }
    private Observation.Node consumeBatch(Observation observation,CaseSpec.Step intent) {
        if(batchTargets.isEmpty()) return null;
        if(!Objects.equals(batchContext,context(observation))) {
            batchTargets.clear();batchContext=null;events.accept(Map.of("kind","ai-batch-invalidated","reason","UI_CONTEXT_CHANGED"));return null;
        }
        var saved=batchTargets.remove(targetKey(intent));if(saved==null) return null;
        var matches=observation.nodes().stream().filter(n -> n.target().equals(saved.target()) && n.attributes().equals(saved.attributes()) && permittedRole(intent.kind(),n.target().role())).toList();
        if(matches.size()!=1) return null;
        events.accept(Map.of("kind","ai-batch-target-reused","action",intent.kind()));return matches.getFirst();
    }
    private Observation.Node discoverBatch(CaseSpec spec,int position,Observation observation,CaseSpec.Step first,Map<String,String> params,Deadline deadline,Set<Integer> retained) {
        var workflow=config.agent().workflow();
        if(!(model instanceof AiWorkflowClient) || !workflow.batchCaseTargets() || !workflow.reuseReadOnlyResponses()) return null;
        Map<String,CaseSpec.Step> intents=new LinkedHashMap<>();Map<String,Set<String>> kinds=new HashMap<>();
        for(int i=position;i<Math.min(spec.steps().size(),position+workflow.maxTargetsPerBatch()*2);i++) {
            if(retained.contains(i+1)) break;
            var step=spec.steps().get(i);
            if(!CaseSpec.targeted(step.kind()) || !Objects.equals(first.container(),step.container()) || step.recordNameRef()!=null || Set.of("expectHidden","expectCount").contains(step.kind())) break;
            if(targets.find(step,observation,params)!=null) continue;
            String key=targetKey(step);if(!intents.containsKey(key) && intents.size()==workflow.maxTargetsPerBatch()) break;
            intents.putIfAbsent(key,step);kinds.computeIfAbsent(key,k -> new TreeSet<>()).add(step.kind());
        }
        if(intents.size()<2) return null;
        List<Map<String,Object>> manifest=new ArrayList<>();Map<String,String> keys=new LinkedHashMap<>();Map<String,Observation.Node> candidates=new LinkedHashMap<>();
        for(var item:intents.entrySet()) {
            String id="t"+keys.size();keys.put(id,item.getKey());
            manifest.add(Map.of("id",id,"instruction",secrets.redact(item.getValue().instruction()),"kinds",kinds.get(item.getKey())));
            project(observation,item.getValue(),params).nodes().forEach(n -> candidates.put(n.id(),n));
        }
        var projected=new Observation(observation.revision(),observation.capturedAt(),LocatorEntry.routeKey(observation.route()),List.copyOf(candidates.values()),false,"COMPLETE","SAFE",null);
        String instruction="Identify each supplied intent in this CURRENT visible UI only. Return finish PASSED with data {targets:[{id,nodeId}]} covering every supplied intent once. " +
            "A node must support ALL supplied kinds for that intent. For absent, ambiguous or future controls return nodeId:null; never guess. Do not act or alter expected results.";
        var request=new ModelClient.Request("case-target-batch",instruction,Map.of("targets",manifest),projected,List.of(),BATCH_SCHEMA);
        if(!((AiWorkflowClient)model).canReserve(request)) {
            events.accept(Map.of("kind","ai-batch-deferred","reason","RESERVATION_LIMIT"));return null;
        }
        var response=ask(request.kind(),request.instruction(),request.params(),projected,BATCH_SCHEMA,deadline.child(Duration.ofSeconds(config.agent().actTimeoutSeconds())));
        batchTargets.clear();batchContext=context(observation);
        for(var target:response.data().path("targets")) if(!target.path("nodeId").isNull()) {
            var node=observation.nodes().stream().filter(n -> n.id().equals(target.path("nodeId").asString())).findFirst().orElseThrow();
            batchTargets.put(keys.get(target.path("id").asString()),new Observation.Node("",node.target(),"","",node.disabled(),node.checked(),node.attributes()));
        }
        events.accept(Map.of("kind","ai-targets-batched","intents",manifest.size(),"resolved",batchTargets.size()));
        var selected=batchTargets.remove(targetKey(first));
        Failure.require(selected!=null,"CASE_LEARNING_BLOCKED","AI could not identify the current target in the supplied batch");
        return observation.nodes().stream().filter(n -> n.target().equals(selected.target()) && n.attributes().equals(selected.attributes())).findFirst().orElseThrow();
    }
    private Observation project(Observation observed,CaseSpec.Step step,Map<String,String> params) {
        var nodes=observed.nodes().stream().filter(n -> permittedRole(step.kind(),n.target().role()) &&
            !Json.string(n.target()).contains("[REDACTED]") && (!"true".equals(n.attributes().get("recordRow")) || "true".equals(n.attributes().get("recordLink"))) &&
            (n.target().name()!=null || n.target().label()!=null) &&
            (!"true".equals(n.attributes().get("recordLink")) || step.recordNameRef()!=null && Objects.equals(n.target().name(),params.get(step.recordNameRef()))))
            .map(n -> new Observation.Node(n.id(),"true".equals(n.attributes().get("recordLink"))?
                new SemanticTarget("link","${recordName}",null,n.target().container()):n.target(),"","",n.disabled(),n.checked(),
                "true".equals(n.attributes().get("recordLink"))?Map.of("recordLink","true"):n.attributes())).toList();
        return new Observation(observed.revision(),observed.capturedAt(),LocatorEntry.routeKey(observed.route()),nodes,false,"COMPLETE","SAFE",null);
    }
    static boolean permittedRole(String kind,String role) {
        if(role==null) return false;
        return switch(kind) {
            case "fill","richText","expectValue" -> Set.of("textbox","searchbox","spinbutton","combobox").contains(role);
            case "upload" -> Set.of("textbox","button").contains(role);
            case "gridFind" -> Set.of("grid","table","treegrid").contains(role);
            case "select","lookup","multiSelect" -> role.equals("combobox");
            case "workspaceTab" -> role.equals("tab");
            case "setChecked","expectChecked" -> Set.of("checkbox","radio","switch").contains(role);
            case "click","download","clickPopup","clickDialog" -> Set.of("button","link","tab","menuitem","treeitem","option","radio","checkbox").contains(role);
            default -> true;
        };
    }
    private static void validateParameters(CaseSpec spec,Set<String> names) {
        for(var step:spec.steps()) Failure.require((step.valueRef()==null || names.contains(step.valueRef())) &&
            (step.recordNameRef()==null || names.contains(step.recordNameRef())),"CASE_PARAMETER_REQUIRED","The case references a parameter that was not supplied");
    }
    private ModelClient.Response ask(String kind,String instruction,Map<String,Object> params,Observation observation,JsonNode schema,Deadline deadline) {
        return CasePlanner.ask(model,budget,kind,instruction,params,observation,schema,deadline);
    }
}
