package hu.ak91.sf.e2e.core;

import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.core.locator.LocatorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CaseMaintenanceTest {
    @TempDir Path root;
    private CaseRecipe baseline() {
        return new CaseRecipe(1,"historical-case",Json.hash("fixture"),"fixture","/app",List.of(
            new CaseRecipe.Step("fill","shared","reference",null,"input",null),
            new CaseRecipe.Step("click","shared","save",null,null,null),
            new CaseRecipe.Step("expectText","shared","result",null,"expected",null)));
    }
    private CaseSpec.Step fill() {return new CaseSpec.Step("fill","New field","dialog",null,"newInput",null);}
    private CaseMaintenance.Changes changes(CaseMaintenance.Insertion... insertions) {
        return new CaseMaintenance.Changes(1,Json.hash(baseline()),List.of(insertions));
    }
    @Test void additionsRetainEveryOriginalOperationControlAndOracleInOrder() {
        var plan=CaseMaintenance.plan(baseline(),changes(new CaseMaintenance.Insertion(2,List.of(fill()))));
        assertEquals(4,plan.steps().size());assertEquals(fill(),plan.steps().get(1).inserted());
        assertEquals(baseline().steps(),plan.steps().stream().filter(s -> s.retained()!=null).map(CaseMaintenance.PlannedStep::retained).toList());
        assertEquals(List.of(1,2,3),plan.steps().stream().filter(s -> s.retained()!=null).map(CaseMaintenance.PlannedStep::baselineStep).toList());
        assertEquals(Set.of(1,3,4),plan.retainedSteps().keySet());assertTrue(plan.needsLearning());
        assertEquals(Set.of("input","expected","newInput"),plan.requiredParams());assertEquals("historical-case",plan.baseline().id());
    }
    @Test void locatorOnlyMaintenanceHasNoLearningAndDoesNotChangeRecipeSerialization() {
        var plan=CaseMaintenance.plan(baseline(),null);assertFalse(plan.needsLearning());
        assertEquals(Json.hash(baseline()),plan.changes().baselineChecksum());assertEquals(baseline().steps(),List.copyOf(plan.retainedSteps().values()));
    }
    @Test void descriptiveDesignAcceptsHistoricalIdsWithoutApplyingNewCaseNamingLimits() {
        for(String id:List.of("h".repeat(70),"TST_")) {
            var old=new CaseRecipe(1,id,Json.hash("fixture"),"fixture","/app",List.of(new CaseRecipe.Step("expectRoute",null,null,"/app",null,null)));
            var design=CaseMaintenance.plan(old,null).design(new LocatorStore(root,"fixture","read-only"));
            assertTrue(design.id().startsWith("TST_maintenance_"));assertEquals(id,CaseMaintenance.plan(old,null).baseline().id());
        }
    }
    @Test void staleRevisionPatchCannotBeAppliedToAnotherBaseline() {
        var patch=new CaseMaintenance.Changes(1,Json.hash("other"),List.of());
        assertEquals("CASE_MAINTENANCE_CONFLICT",assertThrows(Failure.class,() -> CaseMaintenance.plan(baseline(),patch)).code());
    }
    @Test void duplicatedReorderedOrOutOfRangeInsertionPositionsAreRejected() {
        for(var positions:List.of(List.of(2,2),List.of(3,1),List.of(5))) {
            var patch=changes(positions.stream().map(i -> new CaseMaintenance.Insertion(i,List.of(fill()))).toArray(CaseMaintenance.Insertion[]::new));
            assertEquals("CASE_MAINTENANCE_INVALID",assertThrows(Failure.class,() -> CaseMaintenance.plan(baseline(),patch)).code());
        }
    }
    @Test void appendedActionsNeedAnExplicitNewFinalAssertion() {
        assertThrows(Failure.class,() -> CaseMaintenance.plan(baseline(),changes(new CaseMaintenance.Insertion(4,List.of(fill())))));
        var check=new CaseSpec.Step("expectValue","New field","dialog",null,"newExpected",null);
        var plan=CaseMaintenance.plan(baseline(),changes(new CaseMaintenance.Insertion(4,List.of(fill(),check))));
        assertEquals(check,plan.steps().getLast().inserted());assertEquals(5,plan.steps().size());
    }
    @Test void boundedStepsAndParametersAreValidatedBeforeExecution() {
        assertThrows(Failure.class,() -> CaseMaintenance.plan(baseline(),changes(new CaseMaintenance.Insertion(1,Collections.nCopies(198,fill())))));
        var plan=CaseMaintenance.plan(baseline(),changes(new CaseMaintenance.Insertion(2,List.of(fill()))));
        assertEquals("CASE_PARAMETER_REQUIRED",assertThrows(Failure.class,() -> plan.validateParams(Map.of("input","x","expected","x"))).code());
        plan.validateParams(Map.of("input","x","expected","x","newInput","private"));
        var booleanStep=new CaseSpec.Step("setChecked","New flag",null,null,"flag",null);
        var invalid=CaseMaintenance.plan(baseline(),changes(new CaseMaintenance.Insertion(2,List.of(booleanStep))));
        assertEquals("CASE_INVALID",assertThrows(Failure.class,() -> invalid.validateParams(Map.of("input","x","expected","x","flag","yes"))).code());
    }
    @Test void dependencyAnalysisIncludesOnlySuppliedSameNamespaceAndCapabilityRecipes() {
        var baseline=baseline();var shared=new CaseRecipe(1,"related",baseline.namespaceHash(),baseline.engineVersion(),"/other",
            List.of(new CaseRecipe.Step("expectVisible","shared","reference",null,null,null)));
        var foreign=new CaseRecipe(1,"foreign",Json.hash("foreign"),"fixture","/app",shared.steps());
        var upgraded=new CaseRecipe(1,"upgraded",baseline.namespaceHash(),"other-engine","/app",shared.steps());
        var independent=new CaseRecipe(1,"independent",baseline.namespaceHash(),"fixture","/app",List.of(new CaseRecipe.Step("expectVisible","other","reference",null,null,null)));
        assertEquals(List.of(new CaseMaintenance.SharedCase("related",Json.hash(shared),List.of("shared/reference"))),
            CaseMaintenance.plan(baseline,null).sharedCases(List.of(shared,foreign,upgraded,independent)));
    }
    @Test void changesSchemaRejectsReplacementFieldsAndInlineRuntimeValues() {
        for(String extra:List.of("\"replacements\":[]","\"values\":{\"expected\":\"guess\"}"))
            assertThrows(Exception.class,() -> Json.MAPPER.readValue("{\"schemaVersion\":1,\"baselineChecksum\":\""+Json.hash(baseline())+"\",\"insertions\":[],"+extra+"}",CaseMaintenance.Changes.class));
    }
}
