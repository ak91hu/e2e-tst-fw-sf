package hu.ak91.sf.e2e.core.locator;

import hu.ak91.sf.e2e.core.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Locator retries happen before dispatch. Dispatched actions are never blindly repeated. */
public final class HealingLocators {
    @FunctionalInterface public interface Advisor {
        String propose(LocatorEntry entry,Observation observation,Deadline deadline);
    }
    private record Resolved(Observation observation,Observation.Node node) {}
    private record Scored(Observation.Node node,double score) {}
    private final UiEngine engine;
    private final LocatorStore store;
    private final FrameworkConfig.Locators policy;
    private final Deadline deadline;
    private final Consumer<Map<String,Object>> events;
    private final Advisor advisor;
    private final Map<String,LocatorEntry> sessionEntries=new HashMap<>();
    public HealingLocators(UiEngine engine,LocatorStore store,FrameworkConfig.Locators policy,Deadline deadline,Consumer<Map<String,Object>> events) {
        this(engine,store,policy,deadline,events,null);
    }
    public HealingLocators(UiEngine engine,LocatorStore store,FrameworkConfig.Locators policy,Deadline deadline,Consumer<Map<String,Object>> events,Advisor advisor) {
        this.engine=engine;this.store=store;this.policy=policy;this.deadline=deadline;this.events=events;this.advisor=advisor;
    }
    public void open(String route) { engine.open(route,deadline); }
    public void click(String page,String key) { action(page,key,"click",null); }
    public void fill(String page,String key,String value) { action(page,key,"fill",value); }
    public void select(String page,String key,String value) { action(page,key,"select",value); }
    public void check(String page,String key,boolean value) { action(page,key,"setChecked",Boolean.toString(value)); }
    public void action(String page,String key,String kind,String value) {
        action(page,key,kind,value,Map.of());
    }
    public void click(String page,String key,Map<String,String> parameters) { action(page,key,"click",null,parameters); }
    public void action(String page,String key,String kind,String value,Map<String,String> parameters) {
        Deadline wait=deadline.child(Duration.ofSeconds(policy.timeoutSeconds()));
        for(int attempt=0;attempt<=policy.retries();attempt++) {
            var resolved=resolve(page,key,wait,parameters);
            try {
                hu.ak91.sf.e2e.core.quality.ExecutionMetrics.measure("locator-action",attempt+1,events,() -> engine.perform(new UiEngine.Action(kind,resolved.node().id(),value),resolved.observation(),deadline));
                events.accept(Map.of("kind","locator-action","page",page,"key",key,"action",kind));return;
            } catch(Failure e) {
                // UiEngine contract: NODE_STALE is a pre-dispatch rejection. Never retry a dispatched action.
                if(!e.code().equals("NODE_STALE") || attempt==policy.retries()) throw e;
                events.accept(Map.of("kind","locator-retry","page",page,"key",key,"attempt",attempt+1,"reason","NODE_STALE"));
                engine.poll(wait);
            }
        }
    }
    public Observation.Node read(String page,String key) { return resolve(page,key).node(); }
    public Observation.Node read(String page,String key,Map<String,String> parameters) { return resolve(page,key,deadline,parameters).node(); }
    public Observation.Node read(String page,String key,Map<String,String> parameters,Deadline wait) { return resolve(page,key,wait,parameters).node(); }
    public void expectValue(String page,String key,String expected) {
        Deadline wait=deadline.child(Duration.ofSeconds(policy.timeoutSeconds()));
        while(true) {
            wait.check();
            var node=resolve(page,key,wait).node();
            if(Objects.equals(expected,node.value())) { asserted(page,key,"expectValue");return; }
            engine.poll(wait);
        }
    }
    public void expectText(String page,String key,String expected) {
        Deadline wait=deadline.child(Duration.ofSeconds(policy.timeoutSeconds()));
        while(true) {
            wait.check();
            var node=resolve(page,key,wait).node();
            if(Objects.equals(expected,node.text())) { asserted(page,key,"expectText");return; }
            engine.poll(wait);
        }
    }
    public void expectEnabled(String page,String key) { expectDisabledState(page,key,false); }
    public void expectDisabled(String page,String key) { expectDisabledState(page,key,true); }
    private void expectDisabledState(String page,String key,boolean disabled) {
        Deadline wait=deadline.child(Duration.ofSeconds(policy.timeoutSeconds()));
        while(true) {
            try { if(read(page,key,Map.of(),wait).disabled()==disabled) { asserted(page,key,disabled?"expectDisabled":"expectEnabled");return; }engine.poll(wait); }
            catch(Failure f) { if(f.code().equals("STEP_TIMEOUT")) throw new Failure("UI_ASSERTION_FAILED","Expected enabled/disabled state was not satisfied");throw f; }
        }
    }
    public void expectHidden(String page,String key) { expectCount(page,key,0); }
    public void expectCount(String page,String key,int expected) {
        Failure.require(expected>=0 && expected<=10000,"CASE_INVALID","Expected count must be from 0 to 10000");
        Deadline wait=deadline.child(Duration.ofSeconds(policy.timeoutSeconds()));int stable=0;
        while(true) {
            try {
                stable=visibleCount(page,key,Map.of(),wait,expected==0)==expected?stable+1:0;
                if(stable>=3) { asserted(page,key,expected==0?"expectHidden":"expectCount");return; }engine.poll(wait);
            } catch(Failure f) { if(f.code().equals("STEP_TIMEOUT")) throw new Failure("UI_ASSERTION_FAILED","Expected count/absence was not satisfied");throw f; }
        }
    }
    private void asserted(String page,String key,String assertion) { events.accept(Map.of("kind","assertion-passed","page",page,"key",key,"assertion",assertion)); }
    /** Counts never heal the semantic description to make an expected result true. */
    public long visibleCount(String page,String key,Map<String,String> parameters,Deadline wait,boolean provingAbsence) {
        var loaded=sessionEntries.getOrDefault(page+"/"+key,store.get(page,key));
        var entry=loaded.parameterized()?loaded.bind(parameters):loaded;
        Failure.require(loaded.parameterized() || parameters.isEmpty(),"LOCATOR_INVALID","Unexpected locator parameters");
        Failure.require(entry.engineVersion().equals(engine.capabilityVersion()),"LOCATOR_ENGINE_CHANGED","Remap after an engine upgrade");
        var observation=engine.observe(Objects.toString(entry.primary().container(),"page"),wait);
        Failure.require(observation.complete(),"LOCATOR_INCOMPLETE","Incomplete observation cannot establish a count or absence");
        Failure.require(entry.route().equals(LocatorEntry.routeKey(observation.route())),"LOCATOR_WRONG_CONTEXT","Count belongs to a different route");
        List<String> anchors=List.of("testId","apiName").stream().filter(a -> entry.attributes().containsKey(a)).toList();
        if(provingAbsence) Failure.require(!anchors.isEmpty(),"NEGATIVE_ASSERTION_UNPROVABLE","Absence requires a previously observed target with a stable testId/apiName anchor");
        return observation.nodes().stream().filter(n -> frameMatches(entry,n) && LocatorEntry.matches(entry.primary(),n.target()) ||
            provingAbsence && anchors.stream().anyMatch(a -> entry.attributes().get(a).equals(n.attributes().get(a)))).count();
    }
    private Resolved resolve(String page,String key) { return resolve(page,key,deadline); }
    private Resolved resolve(String page,String key,Deadline parent) {
        return resolve(page,key,parent,Map.of());
    }
    private Resolved resolve(String page,String key,Deadline parent,Map<String,String> parameters) {
        var loaded=sessionEntries.containsKey(page+"/"+key)?sessionEntries.get(page+"/"+key):store.get(page,key);
        boolean parameterized=loaded.parameterized();
        if(!parameterized) Failure.require(parameters.isEmpty(),"LOCATOR_INVALID","This locator has no parameters");
        var entry=parameterized?loaded.bind(parameters):loaded;
        final var targetEntry=entry;
        Failure.require(entry.engineVersion().equals(engine.capabilityVersion()),"LOCATOR_ENGINE_CHANGED","Remap locators after an engine capability upgrade");
        Deadline wait=parent.child(Duration.ofSeconds(policy.timeoutSeconds()));
        Observation snapshot=null; String reason="TARGET_NOT_FOUND";
        for(int attempt=0;attempt<=policy.retries();attempt++) {
            wait.check(); snapshot=engine.observe(Objects.toString(entry.primary().container(),"page"),wait);
            Failure.require(entry.route().equals(LocatorEntry.routeKey(snapshot.route())),"LOCATOR_WRONG_CONTEXT","Locator belongs to a different route");
            Failure.require(snapshot.complete(),"LOCATOR_INCOMPLETE","Incomplete observation cannot justify a locator match or repair");
            var exact=snapshot.nodes().stream().filter(n -> LocatorEntry.matches(targetEntry.primary(),n.target())).toList();
            if(exact.size()==1 && !conflictingAnchors(entry,exact.getFirst()) &&
                frameMatches(entry,exact.getFirst()) && (!parameterized || "true".equals(exact.getFirst().attributes().get("recordLink"))))
                return new Resolved(snapshot,exact.getFirst());
            reason=exact.size()>1?"TARGET_AMBIGUOUS":exact.size()==1?"TARGET_IDENTITY_CHANGED":"TARGET_NOT_FOUND";
            // Poll exact matches first: temporary loading/duplicates must not cause immediate healing.
            if(attempt<policy.retries()) {
                events.accept(Map.of("kind","locator-retry","page",page,"key",key,"attempt",attempt+1,"reason",reason));
                for(int elapsed=0;elapsed<policy.retryDelayMillis();elapsed+=100) engine.poll(wait);
            }
        }
        // A different record is never a healing candidate. Bound business values are never persisted.
        Failure.require(!parameterized && !policy.strict() && policy.healing(),"LOCATOR_STALE", "Stored locator is stale: "+page+"/"+key+" ("+reason+")");
        // Duplicated exact targets must never be evaded by a looser description.
        Failure.require(!reason.equals("TARGET_AMBIGUOUS"),"TARGET_AMBIGUOUS","Stored locator now matches multiple controls");
        Observation current=snapshot;
        Set<String> alternativeIds=new HashSet<>();
        for(var target:entry.alternatives()) {
            var matches=current.nodes().stream().filter(n -> LocatorEntry.matches(target,n.target()) && compatible(entry,n) && !conflictingAnchors(entry,n)).toList();
            if(matches.size()==1) alternativeIds.add(matches.getFirst().id());
        }
        Failure.require(alternativeIds.size()<=1,"TARGET_AMBIGUOUS","Stored locator alternatives disagree");
        if(alternativeIds.size()==1) {
            var node=current.nodes().stream().filter(n -> alternativeIds.contains(n.id())).findFirst().orElseThrow();
            return accept(entry,current,node,"stored-alternative",1.0);
        }
        var ranked=current.nodes().stream().filter(n -> compatible(entry,n)).map(n -> new Scored(n,score(entry,n)))
            .sorted(Comparator.comparingDouble(Scored::score).reversed()).toList();
        if(!ranked.isEmpty()) {
            var best=ranked.getFirst(); double second=ranked.size()>1?ranked.get(1).score():0;
            if(best.score()>=policy.minConfidence() && best.score()-second>=policy.minMargin())
                return accept(entry,current,best.node(),"fingerprint",best.score());
        }
        if(policy.aiFallback() && advisor!=null) {
            String nodeId=advisor.propose(entry,current,wait);
            var nodes=current.nodes().stream().filter(n -> n.id().equals(nodeId) && compatible(entry,n)).toList();
            Failure.require(nodes.size()==1,"MODEL_OUTPUT_INVALID","AI repair must reference one compatible current node");
            var node=nodes.getFirst();
            // AI cannot override independent identity confidence or ambiguity checks.
            double confidence=score(entry,node);
            double other=ranked.stream().filter(n -> !n.node().id().equals(nodeId)).mapToDouble(Scored::score).max().orElse(0);
            Failure.require(confidence>=policy.minConfidence() && confidence-other>=policy.minMargin(),"LOCATOR_LOW_CONFIDENCE","AI repair lacks independent identity evidence");
            return accept(entry,current,node,"ai-assisted",confidence);
        }
        throw new Failure("LOCATOR_LOW_CONFIDENCE","Cannot safely heal locator: "+page+"/"+key+"; remap or review the UI");
    }
    private Resolved accept(LocatorEntry entry,Observation snapshot,Observation.Node node,String reason,double confidence) {
        Failure.require(snapshot.nodes().stream().filter(n -> LocatorEntry.matches(node.target(),n.target())).count()==1,
            "TARGET_AMBIGUOUS","Healed semantic locator must remain unique");
        var updated=entry.healed(node,snapshot,reason);
        if(store.writable()) store.putAll(List.of(updated));
        else sessionEntries.put(entry.page()+"/"+entry.key(),updated);
        events.accept(Map.of("kind","locator-healed","page",entry.page(),"key",entry.key(),"reason",reason,
            "confidence",confidence,"persisted",store.writable(),"before",entry.primary(),"after",node.target()));
        return new Resolved(snapshot,node);
    }
    private static boolean compatible(LocatorEntry entry,Observation.Node node) {
        return Objects.equals(entry.primary().role(),node.target().role()) && Objects.equals(entry.primary().container(),node.target().container()) && frameMatches(entry,node);
    }
    private static boolean frameMatches(LocatorEntry entry,Observation.Node node) {
        return !entry.attributes().containsKey("frame") || Objects.equals(entry.attributes().get("frame"),node.attributes().get("frame"));
    }
    private static double score(LocatorEntry entry,Observation.Node node) {
        // Semantic fallback needs several independent anchors. No position or field value guesses.
        var a=entry.attributes(); var b=node.attributes();
        if(conflictingAnchors(entry,node)) return 0;
        for(String anchor:List.of("testId","apiName")) {
            if(a.containsKey(anchor) && a.get(anchor).equals(b.get(anchor)) &&
                Objects.equals(a.get("tag"),b.get("tag")) && Objects.equals(a.get("type"),b.get("type")) &&
                Objects.equals(a.get("group"),b.get("group")) && Objects.equals(a.get("frame"),b.get("frame"))) return 0.99;
        }
        double name=similarity(entry.primary().name(),node.target().name());
        double label=similarity(entry.primary().label(),node.target().label());
        double context=Objects.equals(a.get("group"),b.get("group")) && a.containsKey("group")?1:0;
        double shape=Objects.equals(a.get("tag"),b.get("tag")) && a.containsKey("tag") && Objects.equals(a.get("type"),b.get("type"))?1:0;
        return 0.45*name+0.30*label+0.15*context+0.10*shape;
    }
    private static boolean conflictingAnchors(LocatorEntry entry,Observation.Node node) {
        for(String anchor:List.of("testId","apiName","frame","group","type")) {
            String old=entry.attributes().get(anchor),current=node.attributes().get(anchor);
            if(old!=null && current!=null && !old.equals(current)) return true;
        }
        return false;
    }
    private static double similarity(String first,String second) {
        if(first==null || second==null || first.isBlank() || second.isBlank()) return 0;
        String a=first.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();
        String b=second.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();
        if(a.equals(b)) return 1;
        Set<String> x=new HashSet<>(List.of(a.split(" +"))), y=new HashSet<>(List.of(b.split(" +")));
        Set<String> intersection=new HashSet<>(x);intersection.retainAll(y);x.addAll(y);
        return x.isEmpty()?0:(double)intersection.size()/x.size();
    }
}
