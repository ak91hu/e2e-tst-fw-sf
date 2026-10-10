package hu.ak91.sf.e2e.core.scenario;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.LocatorStore;
import java.util.*;

/** Additive, caller-owned maintenance. Existing actions, controls and oracles cannot be rewritten. */
public final class CaseMaintenance {
    private CaseMaintenance() {}
    public record Insertion(int beforeStep,List<CaseSpec.Step> steps) {
        public Insertion {
            Failure.require(beforeStep>0 && steps!=null && !steps.isEmpty() && steps.size()<=200 && steps.stream().allMatch(Objects::nonNull),
                "CASE_MAINTENANCE_INVALID","An insertion needs a positive baseline step and 1..200 supplied steps");
            steps=List.copyOf(steps);
        }
    }
    public record Changes(int schemaVersion,String baselineChecksum,List<Insertion> insertions) {
        public Changes {
            Failure.require(schemaVersion==1 && baselineChecksum!=null && baselineChecksum.matches("[a-f0-9]{64}") &&
                insertions!=null && insertions.size()<=200 && insertions.stream().allMatch(Objects::nonNull),
                "CASE_MAINTENANCE_INVALID","Maintenance needs schemaVersion 1, a baseline checksum and bounded insertions");
            insertions=List.copyOf(insertions);
        }
    }
    public record PlannedStep(int step,Integer baselineStep,CaseRecipe.Step retained,CaseSpec.Step inserted) {}
    public record SharedCase(String caseId,String recipeChecksum,List<String> sharedControls) {}
    public record Plan(CaseRecipe baseline,Changes changes) {
        public Plan {
            Failure.require(baseline!=null && changes!=null && Json.hash(baseline).equals(changes.baselineChecksum()),
                "CASE_MAINTENANCE_CONFLICT","Maintenance changes belong to a different recipe revision");
            int last=0,total=baseline.steps().size();
            for(var insertion:changes.insertions()) {
                Failure.require(insertion.beforeStep()>last && insertion.beforeStep()<=baseline.steps().size()+1,
                    "CASE_MAINTENANCE_INVALID","Insertions must use unique ascending baseline positions, including the optional append position");
                last=insertion.beforeStep();total+=insertion.steps().size();
            }
            Failure.require(total<=200,"CASE_MAINTENANCE_INVALID","Maintained recipes are limited to 200 steps");
            if(last==baseline.steps().size()+1)
                Failure.require(CaseSpec.assertion(changes.insertions().getLast().steps().getLast().kind()),
                    "CASE_MAINTENANCE_INVALID","Appended maintenance steps must end in a caller-supplied assertion");
        }
        public boolean needsLearning() { return !changes.insertions().isEmpty(); }
        public List<PlannedStep> steps() {
            var result=new ArrayList<PlannedStep>();int insertion=0;
            for(int old=1;old<=baseline.steps().size()+1;old++) {
                if(insertion<changes.insertions().size() && changes.insertions().get(insertion).beforeStep()==old)
                    for(var step:changes.insertions().get(insertion++).steps()) result.add(new PlannedStep(result.size()+1,null,null,step));
                if(old<=baseline.steps().size()) result.add(new PlannedStep(result.size()+1,old,baseline.steps().get(old-1),null));
            }
            return List.copyOf(result);
        }
        public Map<Integer,CaseRecipe.Step> retainedSteps() {
            var retained=new TreeMap<Integer,CaseRecipe.Step>();
            for(var step:steps()) if(step.retained()!=null) retained.put(step.step(),step.retained());
            return Collections.unmodifiableMap(retained);
        }
        public CaseSpec design(LocatorStore store) {
            var design=new ArrayList<CaseSpec.Step>();
            for(var step:steps()) {
                if(step.inserted()!=null) {design.add(step.inserted());continue;}
                var old=step.retained();String instruction=null,scope=null;
                if(old.page()!=null) {
                    var target=store.get(old.page(),old.key()).primary();
                    instruction="Retain stored control "+old.page()+"/"+old.key();scope=target.container();
                }
                design.add(new CaseSpec.Step(old.kind(),instruction,scope,old.route(),old.valueRef(),old.recordNameRef()));
            }
            // CaseSpec normalizes new IDs; historical recipe IDs (up to 70 chars) must not be renamed or rejected.
            return new CaseSpec("TST_maintenance_"+Json.hash(baseline).substring(0,16),baseline.startRoute(),design);
        }
        public Set<String> requiredParams() {
            var refs=new TreeSet<>(baseline.requiredParams());
            for(var insertion:changes.insertions()) for(var step:insertion.steps()) {
                if(step.valueRef()!=null) refs.add(step.valueRef());if(step.recordNameRef()!=null) refs.add(step.recordNameRef());
            }
            return Collections.unmodifiableSet(refs);
        }
        public void validateParams(Map<String,String> params) {
            var validation=steps().stream().map(s -> s.retained()!=null?s.retained():new CaseRecipe.Step(s.inserted().kind(),
                CaseSpec.targeted(s.inserted().kind())?"validation":null,CaseSpec.targeted(s.inserted().kind())?"validation":null,
                s.inserted().route(),s.inserted().valueRef(),s.inserted().recordNameRef())).toList();
            CaseRecipe.validateParams(requiredParams(),params,validation);
        }
        /** Supplied same-context recipes sharing definitions; this is dependency information, not a UI verdict. */
        public List<SharedCase> sharedCases(List<CaseRecipe> recipes) {
            var controls=controls(baseline);var result=new ArrayList<SharedCase>();
            for(var recipe:recipes) if(recipe.namespaceHash().equals(baseline.namespaceHash()) && recipe.engineVersion().equals(baseline.engineVersion())) {
                var shared=new TreeSet<>(controls(recipe));shared.retainAll(controls);
                if(!shared.isEmpty()) result.add(new SharedCase(recipe.id(),Json.hash(recipe),List.copyOf(shared)));
            }
            return List.copyOf(result);
        }
    }
    public static Plan plan(CaseRecipe baseline,Changes changes) {
        return new Plan(baseline,changes==null?new Changes(1,Json.hash(baseline),List.of()):changes);
    }
    private static Set<String> controls(CaseRecipe recipe) {
        var controls=new TreeSet<String>();for(var step:recipe.steps()) if(step.page()!=null) controls.add(step.page()+"/"+step.key());return controls;
    }
}
