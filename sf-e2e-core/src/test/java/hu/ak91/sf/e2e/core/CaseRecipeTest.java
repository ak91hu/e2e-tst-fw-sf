package hu.ak91.sf.e2e.core;

import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CaseRecipeTest {
    @TempDir Path root;
    private CaseRecipe recipe() {
        return new CaseRecipe(1,"private-flow",Json.hash("org-persona"),"engine","/app",List.of(
            new CaseRecipe.Step("fill","page","field",null,"input",null),
            new CaseRecipe.Step("expectValue","page","field",null,"expected",null)));
    }
    private LocatorStore store() {
        var store=new LocatorStore(root,"org-persona","read-write");
        store.putAll(List.of(new LocatorEntry("page","field","https://org/app","engine",SemanticTarget.label("Name","page"),Map.of(),List.of(),1,List.of())));
        return store;
    }
    @Test void recipeRoundTripsWithoutRuntimeValuesAndDetectsTampering() throws Exception {
        Path file=root.resolve("case.json");recipe().save(file);
        assertEquals(recipe(),CaseRecipe.load(file));assertEquals(Set.of("input","expected"),CaseRecipe.load(file).requiredParams());
        Files.writeString(file,Files.readString(file).replace("private-flow","tampered-flow"));
        assertEquals("CASE_INVALID",assertThrows(Failure.class,() -> CaseRecipe.load(file)).code());
    }
    @Test void wrongPersonaOrDeploymentFailsBeforeAnyUiExecution() {
        assertEquals("CASE_WRONG_CONTEXT",assertThrows(Failure.class,() -> recipe().preflight("other-persona",store(),Map.of("input","x","expected","x"))).code());
    }
    @Test void missingExpectedValuesAndMissingMapsFailPreflight() {
        assertEquals("CASE_PARAMETER_REQUIRED",assertThrows(Failure.class,() -> recipe().preflight("org-persona",store(),Map.of("input","x"))).code());
        assertEquals("LOCATOR_NOT_MAPPED",assertThrows(Failure.class,() -> recipe().preflight("org-persona",new LocatorStore(root.resolve("empty"),"org-persona","read-only"),Map.of("input","x","expected","x"))).code());
    }
    @Test void unverifiedActionsUnsupportedKindsAndAuthenticationRoutesCannotBeRecipes() {
        assertThrows(Failure.class,() -> new CaseSpec("bad","/app",List.of(new CaseSpec.Step("click","Continue",null,null,null,null))));
        assertThrows(Failure.class,() -> new CaseRecipe.Step("evaluate","p","x",null,null,null));
        assertThrows(Failure.class,() -> new CaseSpec.Step("open",null,null,"/secur/frontdoor.jsp?sid=secret",null,null));
    }
    @Test void checkedStateAndKeysMustBeValidatedBeforeDispatch() {
        var steps=List.of(new CaseRecipe.Step("setChecked","p","flag",null,"flag",null));
        assertThrows(Failure.class,() -> CaseRecipe.validateParams(Set.of("flag"),Map.of("flag","yes"),steps));
        assertThrows(Failure.class,() -> CaseRecipe.validateParams(Set.of("key"),Map.of("key","Control+R"),List.of(new CaseRecipe.Step("press","p","x",null,"key",null))));
    }
    @Test void incorrectExpectationFailsWithoutRepeatingTheAction() {
        var engine=new Engine();var defaults=FrameworkConfig.defaults();
        var config=new FrameworkConfig(1,"checks","checks",defaults.org(),defaults.ui(),defaults.auth(),defaults.personas(),
            new FrameworkConfig.Execution(1,0,5,2,1,false),defaults.agent(),defaults.cache(),defaults.retention(),defaults.evidence(),defaults.report());
        var disk=store();var runner=new CaseRunner(engine,disk,config,Deadline.after(Duration.ofSeconds(5)),e -> {});
        assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> runner.run(recipe(),"org-persona",disk,Map.of("input","actual","expected","different"))).code());
        assertEquals(1,engine.actions);
    }
    private static class Engine implements UiEngine {
        int actions;String value="";
        public void open(String r,Deadline d) {}
        public String route() { return "https://org/app"; }
        public Observation observe(String c,Deadline d) { return new Observation(1,Instant.now(),route(),List.of(new Observation.Node("n",SemanticTarget.label("Name","page"),"",value,false,false)),false,"COMPLETE","SAFE",null); }
        public Observation.Node resolve(SemanticTarget t,Observation o) { return o.nodes().getFirst(); }
        public void perform(Action a,Observation o,Deadline d) { actions++;value=a.value(); }
        public Map<String,String> readVisibleFields(Deadline d) { return Map.of(); }
        public byte[] captureMaskedPng(Deadline d) { return new byte[0]; }
        public void poll(Deadline d) { d.check(); }
        public String capabilityVersion() { return "engine"; }
        public void close() {}
    }
}
