package hu.ak91.sf.e2e.core;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SuiteSelectionTest {
    CaseSuite suite() { return new CaseSuite(1,"suite",List.of(new CaseSuite.Row("seed","a.json",null,null,List.of()),new CaseSuite.Row("flow","b.json",null,null,List.of(),List.of("seed"),Set.of()),new CaseSuite.Row("independent","c.json",null,null,List.of()))); }
    @Test void failuresIncludePassedPrerequisitesAndRejectChangedManifests() {
        var s=suite();assertEquals(List.of("seed","flow"),SuiteSelection.failed(s,SuiteSelection.sourceHash(s),Map.of("seed","PASSED","flow","FAILED")).stream().map(CaseSuite.Row::id).toList());
        assertThrows(Failure.class,() -> SuiteSelection.failed(s,"wrong",Map.of("flow","FAILED")));
        assertEquals("NO_TESTS",assertThrows(Failure.class,() -> SuiteSelection.failed(s,SuiteSelection.sourceHash(s),Map.of("flow","PASSED"))).code());
    }
    @Test void shardsPartitionWithoutBreakingDependenciesOrCreatingEmptySuccess() {
        var s=suite();var a=SuiteSelection.shard(s.cases(),"1/2");var b=SuiteSelection.shard(s.cases(),"2/2");
        assertEquals(3,a.size()+b.size());assertTrue(Collections.disjoint(a,b));
        assertTrue(a.stream().map(CaseSuite.Row::id).toList().containsAll(List.of("seed","flow")) || b.stream().map(CaseSuite.Row::id).toList().containsAll(List.of("seed","flow")));
        assertEquals("NO_TESTS",assertThrows(Failure.class,() -> SuiteSelection.shard(s.cases(),"3/3")).code());
        assertThrows(Failure.class,() -> SuiteSelection.shard(s.cases(),"0/2"));
    }
    @Test void newFingerprintsNormalizeUnorderedResourcesWithoutChangingRows() {
        var row=new CaseSuite.Row("row","recipe.json",null,null,List.of(),List.of(),Set.of("z-lock","a-lock"));
        assertEquals(List.of("a-lock","z-lock"),Json.MAPPER.convertValue(SuiteSelection.rowSnapshot(row).path("resources"),List.class));
        var suite=new CaseSuite(1,"suite",List.of(row));assertEquals(SuiteSelection.sourceHash(suite),SuiteSelection.sourceHash(Json.MAPPER.readValue(Json.string(suite),CaseSuite.class)));
        assertEquals(Set.of("a-lock","z-lock"),row.resources());
    }
}
