package hu.ak91.sf.e2e.core.scenario;
import hu.ak91.sf.e2e.core.*;
import java.util.*;

/** Consumer-owned boundaries and independent oracles for autonomous workflow planning. */
public record ExplorationCharter(int schemaVersion,String id,String goal,List<String> routes,Set<String> allowedActions,Set<String> allowedInstructions,
                                 List<CaseSpec.Step> oracles,int maxCases,int maxSteps) {
    public ExplorationCharter {
        id=CaseNames.generated(id);
        Failure.require(schemaVersion==1 && goal!=null && !goal.isBlank() && goal.length()<=8000,"CASE_INVALID","Charter requires schema 1 and a bounded goal");
        Failure.require(routes!=null && !routes.isEmpty() && routes.size()<=20 && routes.stream().distinct().count()==routes.size(),"CASE_INVALID","Charter requires 1..20 distinct authorized routes");
        routes.forEach(FrameworkConfig::validateRoute);routes=List.copyOf(routes);
        allowedActions=allowedActions==null?Set.of():Set.copyOf(allowedActions);
        Failure.require(Set.of("click","fill","select","setChecked","press","lookup","multiSelect","richText","workspaceTab","gridFind").containsAll(allowedActions),"CASE_INVALID","Charter contains unsupported or unrestricted actions");
        allowedInstructions=allowedInstructions==null?Set.of():Set.copyOf(allowedInstructions);
        Failure.require(allowedInstructions.size()<=100 && allowedInstructions.stream().allMatch(s -> !s.isBlank() && s.length()<=2000) && (allowedActions.isEmpty() || !allowedInstructions.isEmpty()),"CASE_INVALID","Actions need consumer-owned allowed instructions (maximum 100)");
        Failure.require(oracles!=null && !oracles.isEmpty() && oracles.size()<=50 && oracles.stream().allMatch(s -> s!=null && CaseSpec.assertion(s.kind())),"CASE_INVALID","Charter requires caller-owned deterministic oracles");
        oracles=List.copyOf(oracles);Failure.require(maxCases>=1 && maxCases<=20 && maxSteps>=1 && maxSteps<=100,"CASE_INVALID","Charter supports 1..20 cases and 1..100 steps per case");
    }
    public String fingerprint() {
        var node=(tools.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(this);node.set("allowedActions",Json.MAPPER.valueToTree(allowedActions.stream().sorted().toList()));node.set("allowedInstructions",Json.MAPPER.valueToTree(allowedInstructions.stream().sorted().toList()));return Json.hash(node);
    }
    public void validate(CaseSpec spec,Set<String> parameterNames) {
        Failure.require(routes.contains(spec.startRoute()) && spec.steps().size()<=maxSteps,"MODEL_OUTPUT_INVALID","Generated case exceeds charter route/step bounds");
        for(var step:spec.steps()) {
            if(CaseSpec.assertion(step.kind())) Failure.require(oracles.contains(step),"MODEL_OUTPUT_INVALID","AI cannot invent or change a business oracle");
            else if(step.kind().equals("open")) Failure.require(routes.contains(step.route()),"MODEL_OUTPUT_INVALID","AI navigation is outside the charter");
            else Failure.require(allowedActions.contains(step.kind()) && allowedInstructions.contains(step.instruction()),"MODEL_OUTPUT_INVALID","AI action or instruction is outside the charter");
            for(String ref:new String[]{step.valueRef(),step.recordNameRef()}) Failure.require(ref==null || parameterNames.contains(ref),"CASE_PARAMETER_REQUIRED","Generated case requires an undeclared parameter");
        }
    }
    /** An approved intent string cannot authorize the model to select another named control. */
    public void validateTarget(CaseSpec.Step step,SemanticTarget target,Map<String,String> params) {
        String name=target.name();if("${recordName}".equals(name) && step.recordNameRef()!=null) name=params.get(step.recordNameRef());
        Failure.require((CaseSpec.assertion(step.kind()) || allowedActions.contains(step.kind()) && allowedInstructions.contains(step.instruction())) &&
            (step.instruction().equals(name) || step.instruction().equals(target.label())),"CHARTER_TARGET_DENIED","Selected control does not match the consumer-authorized name/label");
    }
}
