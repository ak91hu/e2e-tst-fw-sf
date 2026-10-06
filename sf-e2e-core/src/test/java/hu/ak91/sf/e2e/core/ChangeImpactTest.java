package hu.ak91.sf.e2e.core;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChangeImpactTest {
    CaseSuite suite() {return new CaseSuite(1,"regression",List.of(new CaseSuite.Row("setup","TST_setup.json",null,null,List.of()),new CaseSuite.Row("invoice","TST_invoice.json",null,null,List.of(),List.of("setup"),Set.of()),new CaseSuite.Row("search","TST_search.json",null,null,List.of())));}
    ChangeImpact.Coverage coverage() {return new ChangeImpact.Coverage(1,"reviewed-v1",List.of("sf-e2e.yaml","**/package-lock.json"),Set.of(),List.of(new ChangeImpact.Rule("invoice",List.of("force-app/**/invoice/**"),Set.of("invoice","setup")),new ChangeImpact.Rule("search",List.of("force-app/**/search/**"),Set.of("search"))));}
    ChangeImpact.Plan plan(String... changes) {return ChangeImpact.evaluate(suite(),coverage(),new ChangeImpact.Changes(1,List.of(changes)));}
    @Test void impactedDatasetGetsSetupClosureAndExplicitReasonsBeforeUnrelatedRows() {
        var plan=plan("force-app/main/default/lwc/invoice/invoice.js");assertEquals("IMPACTED",plan.decision());assertEquals(List.of("setup","invoice"),plan.selected());assertFalse(plan.selected().contains("search"));assertTrue(plan.reasons().get("setup").contains("dependency:invoice"));assertEquals(Json.hash(suite()),plan.suiteChecksum());
    }
    @Test void unknownOrGlobalChangesSelectTheFullSuite() {assertEquals("FULL_SUITE",plan("unexpected/new-file.js").decision());assertEquals(3,plan("unexpected/new-file.js").selected().size());assertEquals("FULL_SUITE",plan("sf-e2e.yaml").decision());assertEquals(List.of("unexpected/new-file.js"),plan("unexpected/new-file.js").unknown());}
    @Test void uncoveredDatasetsAndExplicitAlwaysCasesCannotDisappear() {
        var c=new ChangeImpact.Coverage(1,"reviewed",List.of(),Set.of("search"),List.of(new ChangeImpact.Rule("invoice",List.of("invoice/**"),Set.of("invoice"))));
        var plan=ChangeImpact.evaluate(suite(),c,new ChangeImpact.Changes(1,List.of("invoice/file.js")));assertEquals(List.of("setup","invoice","search"),plan.selected());assertEquals(List.of("setup"),plan.uncoveredDatasets());
    }
    @Test void emptyChangesAreAnExplicitNonexecutionRatherThanPassingTests() {assertEquals("NO_CHANGES",plan().decision());assertTrue(plan().selected().isEmpty());}
    @Test void portableGlobsCoverRootsAndNestedFilesButKeepSingleStarsWithinSegments() {
        assertTrue(ChangeImpact.matches("**/package-lock.json","package-lock.json"));assertTrue(ChangeImpact.matches("**/package-lock.json","a/b/package-lock.json"));assertFalse(ChangeImpact.matches("lwc/*/x.js","lwc/a/b/x.js"));assertTrue(ChangeImpact.matches("lwc/?/x.js","lwc/a/x.js"));assertFalse(ChangeImpact.matches("lwc/?/x.js","lwc/ab/x.js"));
    }
    @Test void unsafePathsUnknownDatasetReferencesAndCyclesAreRejected() {
        for(String file:List.of("../escape","C:/absolute","a\\b","a//b","a/./b","a/*")) assertThrows(Failure.class,() -> new ChangeImpact.Changes(1,List.of(file)));
        var wrong=new ChangeImpact.Coverage(1,"review",List.of(),Set.of("unknown"),List.of());assertEquals("IMPACT_INVALID",assertThrows(Failure.class,() -> ChangeImpact.evaluate(suite(),wrong,new ChangeImpact.Changes(1,List.of("a")))).code());
        assertThrows(Failure.class,() -> new CaseSuite(1,"cycle",List.of(new CaseSuite.Row("a","a.json",null,null,List.of(),List.of("b"),Set.of()),new CaseSuite.Row("b","b.json",null,null,List.of(),List.of("a"),Set.of()))));
    }
}
