package hu.ak91.sf.e2e.core.scenario;

import hu.ak91.sf.e2e.core.*;
import java.util.*;

/** User-owned intent. Values and expected results are runtime references, never model guesses. */
public record CaseSpec(String id,String startRoute,List<Step> steps) {
    public static final Set<String> KINDS=Set.of("open","click","fill","select","setChecked","press","upload","download","clickPopup","clickDialog","lookup","multiSelect","richText","workspaceTab","gridFind","switchPage",
        "expectVisible","expectHidden","expectEnabled","expectDisabled","expectCount","expectText","expectValue","expectChecked","expectRoute");
    public record Step(String kind,String instruction,String scope,String route,String valueRef,String recordNameRef) {
        public Step {
            Failure.require(kind!=null && KINDS.contains(kind),"CASE_INVALID","Unsupported case step kind");
            boolean navigation=kind.equals("open") || kind.equals("expectRoute");
            Failure.require(kind.equals("switchPage")?route==null && instruction==null && scope==null && recordNameRef==null:navigation ? route!=null && instruction==null && scope==null && valueRef==null && recordNameRef==null :
                route==null && instruction!=null && !instruction.isBlank() && instruction.length()<=2000,
                "CASE_INVALID","Navigation needs a route; a UI step needs a target instruction");
            if(navigation) FrameworkConfig.validateRoute(route);
            if(scope!=null) Failure.require(!scope.isBlank() && scope.length()<=200,"CASE_INVALID","Invalid observation scope");
            validateRef(recordNameRef);
            Failure.require(needsValue(kind)==(valueRef!=null),"CASE_INVALID","Step value/expectation requires exactly one valueRef");
            validateRef(valueRef);
        }
        public String container() { return scope==null?"page":scope; }
    }
    public CaseSpec {
        id=CaseNames.generated(id);
        FrameworkConfig.validateRoute(startRoute);
        Failure.require(steps!=null && !steps.isEmpty() && steps.size()<=200 && steps.stream().allMatch(Objects::nonNull),"CASE_INVALID","Case needs 1..200 steps");
        steps=List.copyOf(steps);
        Failure.require(steps.stream().anyMatch(s -> assertion(s.kind())),"CASE_INVALID","Case requires an explicit deterministic assertion");
        Failure.require(assertion(steps.getLast().kind()),"CASE_INVALID","End the case with an assertion, not an unverified action");
    }
    public static boolean assertion(String kind) { return kind.startsWith("expect"); }
    public static boolean targeted(String kind) { return !Set.of("open","expectRoute","switchPage").contains(kind); }
    public static boolean needsValue(String kind) { return Set.of("fill","select","setChecked","press","upload","download","clickPopup","clickDialog","lookup","multiSelect","richText","gridFind","switchPage","expectCount","expectText","expectValue","expectChecked").contains(kind); }
    public static void validateRef(String ref) {
        Failure.require(ref==null || ref.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,99}"),"CASE_INVALID","Invalid parameter reference");
    }
}
