package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.CaseSpec;
import java.util.*;

/** Repository-first lookup using current complete evidence, never a saved live node or an AI verdict. */
final class CaseTargetReuse {
    private final LocatorStore store;
    private final String engine;
    private final CaseTargetIndex index;
    CaseTargetReuse(LocatorStore store,String engine) {this.store=store;this.engine=engine;index=new CaseTargetIndex(store);}

    LocatorEntry find(CaseSpec.Step intent,Observation observation,Map<String,String> parameters) {
        Failure.require(observation.complete(),"LOCATOR_INCOMPLETE","Incomplete UI cannot justify repository target reuse");
        var entries=store.entries();var references=index.references(intent,LocatorEntry.routeKey(observation.route()),engine);
        Set<String> associated=new HashSet<>();
        for(var ref:references) {
            var entry=entries.get(ref.page()+"/"+ref.key());
            if(entry!=null && CaseTargetIndex.identity(entry).equals(ref.identity())) associated.add(ref.page()+"/"+ref.key());
        }
        var candidates=new TreeMap<String,LocatorEntry>();var nodes=new HashSet<String>();
        for(var entry:entries.values()) {
            var node=match(entry,intent,observation,parameters);
            if(node!=null && (associated.contains(entry.page()+"/"+entry.key()) || intent.recordNameRef()==null &&
                List.of(Objects.toString(node.target().name(),""),Objects.toString(node.target().label(),""),
                    node.attributes().getOrDefault("apiName",""),node.attributes().getOrDefault("testId","")).contains(intent.instruction().strip()))) {
                candidates.put(entry.page()+"/"+entry.key(),entry);nodes.add(node.id());
            }
        }
        // Historical duplicate definitions may refer to one current control. Never pick between controls.
        return nodes.size()==1?candidates.firstEntry().getValue():null;
    }
    LocatorEntry existing(LocatorEntry proposed,CaseSpec.Step intent,Observation observation,Observation.Node selected,Map<String,String> parameters) {
        return store.entries().values().stream().filter(entry -> entry.parameterized()==proposed.parameterized())
            .filter(entry -> {var node=match(entry,intent,observation,parameters);return node!=null && node.target().equals(selected.target()) && node.attributes().equals(selected.attributes()) ||
                intent.kind().equals("expectCount") && CaseTargetIndex.identity(entry).equals(CaseTargetIndex.identity(proposed));})
            .sorted(Comparator.comparing(entry -> entry.page()+"/"+entry.key())).findFirst().orElse(null);
    }
    boolean stillMatches(LocatorEntry entry,CaseSpec.Step intent,Observation fresh,Map<String,String> parameters) {
        Failure.require(fresh.complete(),"LOCATOR_INCOMPLETE","Incomplete UI cannot authorize repository target reuse");
        Failure.require(entry.equals(store.get(entry.page(),entry.key())),"LOCATOR_CONFLICT","Reusable control changed during learning");
        return match(entry,intent,fresh,parameters)!=null;
    }
    void remember(CaseSpec.Step intent,LocatorEntry entry) {index.remember(intent,entry);}
    private Observation.Node match(LocatorEntry entry,CaseSpec.Step intent,Observation observation,Map<String,String> parameters) {
        if(!entry.engineVersion().equals(engine) || !entry.route().equals(LocatorEntry.routeKey(observation.route())) ||
            !Objects.equals(entry.primary().container(),intent.container()) || entry.parameterized()!=(intent.recordNameRef()!=null)) return null;
        var bound=entry.parameterized()?entry.bind(Map.of("recordName",parameters.get(intent.recordNameRef()))):entry;
        var matches=observation.nodes().stream().filter(node -> LocatorEntry.matches(bound.primary(),node.target())).toList();
        if(!intent.kind().equals("expectCount") && matches.size()!=1) return null;
        for(var node:matches.stream().sorted(Comparator.comparing(Observation.Node::id)).toList()) if(supports(entry,intent,node)) return node;
        return null;
    }
    private boolean supports(LocatorEntry entry,CaseSpec.Step intent,Observation.Node node) {
        if(!CaseLearner.permittedRole(intent.kind(),node.target().role()) || Json.string(node.target()).contains("[REDACTED]") ||
            node.target().name()==null && node.target().label()==null ||
            !Objects.equals(entry.attributes().get("frame"),node.attributes().get("frame"))) return false;
        if(entry.parameterized()) {
            if(!"true".equals(node.attributes().get("recordLink"))) return false;
        } else if("true".equals(node.attributes().get("recordRow")) || "true".equals(node.attributes().get("recordLink"))) return false;
        return entry.attributes().entrySet().stream().allMatch(attribute -> Objects.equals(attribute.getValue(),node.attributes().get(attribute.getKey())));
    }
}
