package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** Bounded text-to-intent planning without a browser, target credentials or locator writes. */
public final class CasePlanner {
    private final ModelClient model;
    private final SecretRegistry secrets;
    private final Budget budget;
    public record Plan(CaseSpec spec,Map<String,String> parameters,List<Integer> sourceSteps,int sourceInstructionCount) {
        public Plan(CaseSpec spec,Map<String,String> parameters) { this(spec,parameters,List.of(),0); }
        public Plan { sourceSteps=List.copyOf(sourceSteps); }
    }
    public CasePlanner(ModelClient model,FrameworkConfig.Agent policy,SecretRegistry secrets) {
        this(model,secrets,new Budget(policy.maxActions(),policy.maxModelCalls(),policy.maxTokens()));
    }
    CasePlanner(ModelClient model,SecretRegistry secrets,Budget budget) {
        this.model=model;this.secrets=secrets;this.budget=budget;
    }
    /** Literal values must occur verbatim in supplied intent; expected values are never taken from the UI. */
    public Plan plan(String id,String route,String instructions,Set<String> parameterNames,Deadline deadline) {
        Failure.require(instructions!=null && !instructions.isBlank() && instructions.length()<=40000,"CASE_INVALID","Instructions must contain 1..40000 characters");
        FrameworkConfig.validateRoute(route);
        id=CaseNames.generated(id);
        parameterNames.forEach(CaseSpec::validateRef);
        var sourceInstructions=instructions.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        Failure.require(sourceInstructions.size()<=200,"CASE_INVALID","Supply at most 200 nonblank instruction lines, in execution order");
        var stepProperties=new TreeMap<String,Object>();
        stepProperties.put("kind",Map.of("type","string","enum",CaseSpec.KINDS.stream().sorted().toList()));
        stepProperties.put("sourceInstruction",Map.of("type","integer","minimum",1,"maximum",sourceInstructions.size()));
        for(String field:List.of("instruction","scope","route","valueRef","recordNameRef")) stepProperties.put(field,Map.of("type",List.of("string","null"),"maxLength",2000));
        JsonNode schema=Json.MAPPER.valueToTree(Map.of("type","object","additionalProperties",false,"required",List.of("steps"),"properties",Map.of("steps",
            Map.of("type","array","minItems",1,"maxItems",200,"items",Map.of("type","object","additionalProperties",false,"required",List.of("kind","sourceInstruction"),"properties",stepProperties)),
            "parameters",Map.of("type","object","additionalProperties",Map.of("type","string","minLength",1,"maxLength",10000)))));
        var response=ask(model,budget,"case-plan","Translate the supplied test case into explicit UI steps, preserving its actions and expected results. " +
            "Do not invent expectations, setup records, extra business actions or parameter values. " +
            "Follow every sourceInstructions line in order. Each step must include its 1-based sourceInstruction number. " +
            "Cover every line without gaps or reordering; several consecutive steps may implement one line. " +
            "Preserve intermediate assertions as well as the final outcome. If a line cannot be implemented, return BLOCKED. " +
            "Values/expected text must use valueRef from parameterNames. If the user supplied literal inputs/expectations in testCase, " +
            "extract them VERBATIM into data.parameters with new stable reference names and use those refs. No generated/computed/default values. " +
            "Do not redefine caller parameters. Ask for missing inputs by returning BLOCKED; never guess. " +
            "UI steps need an instruction identifying the target and optional scope. expectEnabled/expectDisabled verify state; expectCount uses an integer valueRef. " +
            "expectHidden (or count zero) must reuse exactly an earlier visible target's instruction/scope with a stable testId/apiName; do not invent an absent target. " +
            "Open/expectRoute need only kind and an application-relative route. End with an explicit deterministic assertion. " +
            "No deletion or authentication steps. This plans intent only: do not execute actions. Return finish PASSED with data matching the schema.",
            Map.of("testCase",secrets.redact(instructions),"sourceInstructions",sourceInstructions.stream().map(secrets::redact).toList(),"parameterNames",parameterNames),null,schema,deadline);
        try {
            var steps=new ArrayList<CaseSpec.Step>();var sourceSteps=new ArrayList<Integer>();int previous=0;
            for(var raw:response.data().path("steps")) {
                int source=raw.path("sourceInstruction").asInt();
                Failure.require(source==previous || source==previous+1,"CASE_INSTRUCTION_MISMATCH","Generated plan omitted or reordered a source instruction; use one instruction per line or a reviewed --spec");
                previous=source;sourceSteps.add(source);
                var step=raw.deepCopy();((tools.jackson.databind.node.ObjectNode)step).remove("sourceInstruction");
                steps.add(Json.MAPPER.treeToValue(step,CaseSpec.Step.class));
            }
            Failure.require(previous==sourceInstructions.size(),"CASE_INSTRUCTION_MISMATCH","Generated plan omitted source instructions; provide explicit steps or a reviewed --spec");
            var spec=new CaseSpec(id,route,steps);
            Map<String,String> extracted=new TreeMap<>();
            response.data().path("parameters").properties().forEach(e -> {
                CaseSpec.validateRef(e.getKey());String value=e.getValue().asString();
                Failure.require(!parameterNames.contains(e.getKey()) && instructions.contains(value) && !value.contains("[REDACTED]") && !secrets.containsSecret(value),
                    "MODEL_OUTPUT_INVALID","Inferred parameter must be a literal from the supplied test, without redefining caller values");
                extracted.put(e.getKey(),value);
            });
            Failure.require(extracted.size()+parameterNames.size()<=200,"CASE_INVALID","Too many case parameters");
            Set<String> available=new HashSet<>(parameterNames);available.addAll(extracted.keySet());validateParameters(spec,available);
            return new Plan(spec,Map.copyOf(extracted),sourceSteps,sourceInstructions.size());
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("MODEL_OUTPUT_INVALID","Generated case plan has invalid steps"); }
    }
    private static void validateParameters(CaseSpec spec,Set<String> names) {
        var missing=new TreeSet<String>();
        for(var step:spec.steps()) {
            if(step.valueRef()!=null && !names.contains(step.valueRef())) missing.add(step.valueRef());
            if(step.recordNameRef()!=null && !names.contains(step.recordNameRef())) missing.add(step.recordNameRef());
        }
        Failure.require(missing.isEmpty(),"CASE_PARAMETER_REQUIRED","Missing case parameters: "+String.join(", ",missing)+". Provide explicit inputs and expected values.");
    }
    static ModelClient.Response ask(ModelClient model,Budget budget,String kind,String instruction,Map<String,Object> params,Observation observation,JsonNode schema,Deadline deadline) {
        for(int attempt=0;attempt<2;attempt++) {
            budget.call();boolean received=false;
            try {
                var response=model.complete(new ModelClient.Request(kind,instruction,params,observation,List.of(),schema),deadline);
                received=true;Failure.require(response!=null,"MODEL_OUTPUT_INVALID","Empty model response");budget.usage(response.tokens());
                Failure.require("finish".equals(response.tool()) && response.nodeId()==null && response.paramRef()==null && response.value()==null,"MODEL_OUTPUT_INVALID","Case discovery must be read-only structured output");
                if(Set.of("BLOCKED","FAILED").contains(Objects.toString(response.verdict(),""))) throw new Failure("CASE_LEARNING_BLOCKED","AI could not resolve the supplied case; provide explicit steps, scopes and parameter references");
                Failure.require("PASSED".equals(response.verdict()) && response.errorCode()==null,"MODEL_OUTPUT_INVALID","Invalid case discovery verdict");
                JsonSchema.validate(response.data(),schema);return response;
            } catch(Failure e) {
                if(!e.code().equals("MODEL_OUTPUT_INVALID") || attempt==1) throw e;
                if(!received) budget.usage(null);
                instruction+=" Return exactly the required JSON protocol and schema, without Markdown.";
            }
        }
        throw new AssertionError("Unreachable");
    }
}
