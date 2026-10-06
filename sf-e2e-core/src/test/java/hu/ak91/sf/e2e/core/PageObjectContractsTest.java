package hu.ak91.sf.e2e.core;

import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.page.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PageObjectContractsTest {
    @TempDir Path root;
    private LocatorStore store() {
        var disk=new LocatorStore(root,"pom","read-write");
        disk.putAll(List.of(new LocatorEntry("Editor","name",LocatorEntry.routeKey("https://app.example/editor"),"engine",
            SemanticTarget.label("Name","page"),Map.of("apiName","Name__c"),List.of(),1,List.of())));
        return disk;
    }
    private PageObjects pages(Engine engine,LocatorStore store) {
        var deadline=Deadline.after(Duration.ofSeconds(10));
        return new PageObjects(new HealingLocators(engine,store,FrameworkConfig.defaults().locators(),deadline,e -> {}),deadline);
    }
    @Test void wrappersAreReusedButEveryReadAndActionResolvesTheCurrentUi() {
        var engine=new Engine();var disk=store();var pages=pages(engine,disk);var page=pages.page("Editor");
        assertSame(page,pages.page("Editor"));assertNotSame(page,pages(engine,disk).page("Editor"));
        page.fill("name","FIRST");assertEquals("FIRST",page.read("name").value());
        engine.value="EXTERNAL_CHANGE";assertEquals("EXTERNAL_CHANGE",page.read("name").value());
        page.fill("name","SECOND");assertEquals("SECOND",page.read("name").value());
        assertEquals(2,engine.actions);assertEquals(5,engine.observations);
    }
    @Test void wrongRouteAndAmbiguousTargetsCannotDispatchThroughPageObjects() {
        var engine=new Engine();var page=pages(engine,store()).page("Editor");engine.currentRoute="https://app.example/other";
        assertEquals("LOCATOR_WRONG_CONTEXT",assertThrows(Failure.class,() -> page.fill("name","X")).code());
        engine.currentRoute="https://app.example/editor";engine.ambiguous=true;
        assertThrows(Failure.class,() -> page.fill("name","X"));assertEquals(0,engine.actions);
    }
    @Test void uncertainActionsAreNeverRepeatedAndInvalidOperationsFailBeforeObservation() {
        var engine=new Engine();var page=pages(engine,store()).page("Editor");
        assertEquals("CASE_INVALID",assertThrows(Failure.class,() -> page.action("name","expectValue","X")).code());
        assertEquals("CASE_INVALID",assertThrows(Failure.class,() -> page.action("name",null,null)).code());
        assertThrows(Failure.class,() -> pages(engine,new LocatorStore(root,"pom","read-only")).open("/secur/frontdoor.jsp?sid=private"));
        assertThrows(Failure.class,() -> page.action("name","press","Control+R"));
        assertThrows(Failure.class,() -> page.action("name","upload","../private.txt"));assertEquals(0,engine.observations);
        engine.uncertain=true;assertEquals("ACTION_UNCERTAIN",assertThrows(Failure.class,() -> page.fill("name","X")).code());assertEquals(1,engine.actions);
    }
    @Test void sessionObjectsRefuseUseFromAnotherWorker() throws Exception {
        var engine=new Engine();var pages=pages(engine,store());var page=pages.page("Editor");
        try(var worker=Executors.newSingleThreadExecutor()) {
            assertEquals("POLICY_DENIED",worker.submit(() -> assertThrows(Failure.class,() -> page.read("name")).code()).get(3,TimeUnit.SECONDS));
            assertEquals("POLICY_DENIED",worker.submit(() -> assertThrows(Failure.class,() -> pages.page("Editor")).code()).get(3,TimeUnit.SECONDS));
        }
        assertEquals(0,engine.observations);
    }
    @Test void namingAndPageDefinitionsPreserveOldRecipesAndSeparateRoutesAndLearningRevisions() throws Exception {
        assertEquals("TST_flow",CaseNames.generated("flow"));assertEquals("TST_flow",CaseNames.generated("TST_flow"));
        assertThrows(Failure.class,() -> CaseNames.generated("TST_"));assertThrows(Failure.class,() -> CaseNames.generated("x".repeat(67)));
        assertEquals(70,CaseNames.generated("x".repeat(66)).length());
        assertEquals(root.resolve("TST_flow.json"),CaseNames.output(root.resolve("flow.json")));
        assertEquals(root.resolve("TST_flow.json"),CaseNames.output(root.resolve("TST_flow.json")));
        assertNotEquals(PageObjects.modelId("https://app.example/editor","rev"),PageObjects.modelId("https://app.example/view","rev"));
        assertNotEquals(PageObjects.modelId("https://app.example/editor","rev"),PageObjects.modelId("https://app.example/editor","next"));
        var legacy=new CaseRecipe(1,"legacy",Json.hash("pom"),"engine","/editor",List.of(new CaseRecipe.Step("expectValue","Editor","name",null,"expected",null)));
        var file=root.resolve("legacy.json");legacy.save(file);assertEquals(legacy,CaseRecipe.load(file));
        assertEquals(Map.of("Editor",List.of("name")),legacy.pageObjects());
        assertEquals("TST_flow",new CaseSpec("flow","/editor",List.of(new CaseSpec.Step("expectVisible","Name",null,null,null,null))).id());
    }
    private static class Engine implements UiEngine {
        String currentRoute="https://app.example/editor",value="";int observations,actions;boolean ambiguous,uncertain;
        public String route() { return currentRoute; }
        public void open(String route,Deadline deadline) {}
        public Observation observe(String scope,Deadline deadline) {
            observations++;var node=new Observation.Node("n"+observations,SemanticTarget.label("Name","page"),"",value,false,false,Map.of("apiName","Name__c"));
            return new Observation(observations,Instant.now(),route(),ambiguous?List.of(node,node):List.of(node),false,"COMPLETE","SAFE",null);
        }
        public Observation.Node resolve(SemanticTarget target,Observation observation) { return observation.nodes().getFirst(); }
        public void perform(Action action,Observation observation,Deadline deadline) { actions++;if(uncertain) throw new Failure("ACTION_UNCERTAIN","Dispatched result unknown");value=action.value(); }
        public Map<String,String> readVisibleFields(Deadline deadline) { return Map.of(); }
        public byte[] captureMaskedPng(Deadline deadline) { return new byte[0]; }
        public void poll(Deadline deadline) { deadline.check(); }
        public String capabilityVersion() { return "engine"; }
        public void close() {}
    }
}
