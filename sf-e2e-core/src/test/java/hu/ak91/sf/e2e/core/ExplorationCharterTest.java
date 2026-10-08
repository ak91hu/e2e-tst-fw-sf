package hu.ak91.sf.e2e.core;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ExplorationCharterTest {
    final CaseSpec.Step oracle=new CaseSpec.Step("expectText","Result",null,null,"expected",null);
    ExplorationCharter charter() {return new ExplorationCharter(1,"fixture","Verify supplied result",List.of("/app"),Set.of("click","fill"),Set.of("Submit","Input"),List.of(oracle),2,5);}
    CaseSpec spec(CaseSpec.Step step) {return new CaseSpec("fixture","/app",List.of(step,oracle));}
    @Test void callerOwnsActionInstructionsAndOracles() {
        charter().validate(spec(new CaseSpec.Step("click","Submit",null,null,null,null)),Set.of("expected"));
        assertThrows(Failure.class,() -> charter().validate(spec(new CaseSpec.Step("click","Delete everything",null,null,null,null)),Set.of("expected")));
        assertThrows(Failure.class,() -> charter().validate(new CaseSpec("fixture","/app",List.of(new CaseSpec.Step("expectText","Result",null,null,"invented",null))),Set.of("expected","invented")));
    }
    @Test void routesUnknownValuesAndUnrestrictedActionsAreRejected() {
        assertThrows(Failure.class,() -> charter().validate(spec(new CaseSpec.Step("open",null,null,"/other",null,null)),Set.of("expected")));
        assertThrows(Failure.class,() -> charter().validate(spec(new CaseSpec.Step("fill","Input",null,null,"missing",null)),Set.of("expected")));
        assertThrows(Failure.class,() -> new ExplorationCharter(1,"fixture","Goal",List.of("/app"),Set.of("click"),Set.of(),List.of(oracle),1,5));
        assertThrows(Failure.class,() -> new ExplorationCharter(1,"fixture","Goal",List.of("/app"),Set.of("upload"),Set.of("Input"),List.of(oracle),1,5));
    }
    @Test void limitsAndNonAssertionOraclesCannotBeBypassed() {
        assertThrows(Failure.class,() -> new ExplorationCharter(1,"fixture","Goal",List.of("/app"),Set.of(),Set.of(),List.of(oracle),21,5));
        assertThrows(Failure.class,() -> new ExplorationCharter(1,"fixture","Goal",List.of("/app"),Set.of(),Set.of(),List.of(new CaseSpec.Step("click","Submit",null,null,null,null)),1,5));
        assertThrows(Failure.class,() -> charter().validate(new CaseSpec("fixture","/other",List.of(oracle)),Set.of("expected")));
    }
    @Test void approvedInstructionCannotAuthorizeAnotherObservedControl() {
        var step=new CaseSpec.Step("click","Submit",null,null,null,null);
        charter().validateTarget(step,SemanticTarget.role("button","Submit","page"),Map.of());
        assertEquals("CHARTER_TARGET_DENIED",assertThrows(Failure.class,() -> charter().validateTarget(step,SemanticTarget.role("button","Delete everything","page"),Map.of())).code());
    }
}
