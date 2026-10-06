package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import java.util.*;
import java.util.function.Supplier;

/** Optional, lazy and bounded. Deterministic runs never instantiate a provider. */
public final class ModelHealingAdvisor implements HealingLocators.Advisor {
    private final Supplier<ModelClient> client;
    private final Budget budget;
    public ModelHealingAdvisor(Supplier<ModelClient> client,FrameworkConfig.Agent policy) {
        this.client=client;budget=new Budget(policy.maxActions(),policy.maxModelCalls(),policy.maxTokens());
    }
    public String propose(LocatorEntry entry,Observation observation,Deadline deadline) {
        budget.call();
        var schema=Json.MAPPER.valueToTree(Map.of("type","object","required",List.of("nodeId"),"additionalProperties",false,
            "properties",Map.of("nodeId",Map.of("type","string","minLength",1))));
        var projected=new Observation(observation.revision(),observation.capturedAt(),entry.route(),observation.nodes().stream()
            .map(n -> new Observation.Node(n.id(),n.target(),"","",n.disabled(),n.checked(),n.attributes())).toList(),false,"COMPLETE","SAFE",null);
        var response=client.get().complete(new ModelClient.Request("heal","Identify the current control corresponding to the stored locator. " +
            "Use observed nodeId only. Do not act. Return finish PASSED with data {nodeId}. If uncertain return BLOCKED.",
            Map.of("stored",entry),projected,List.of(),schema),deadline);
        budget.usage(response.tokens());
        Failure.require("finish".equals(response.tool()) && "PASSED".equals(response.verdict()) && response.errorCode()==null &&
            response.nodeId()==null && response.value()==null && response.paramRef()==null,"MODEL_OUTPUT_INVALID","AI healing must return a read-only structured finish");
        JsonSchema.validate(response.data(),schema);return response.data().path("nodeId").asText();
    }
}
