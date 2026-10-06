package hu.ak91.sf.e2e.core;

import hu.ak91.sf.e2e.core.locator.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HealingLocatorsTest {
    @TempDir Path root;
    private final Deadline deadline=Deadline.after(Duration.ofSeconds(10));
    private static Observation.Node node(String id,String name) {
        return new Observation.Node(id,new SemanticTarget("button",name,null,"page"),name,"",false,false,Map.of("tag","button","testId","submit","frame","main"));
    }
    private static Observation snapshot(String route,List<Observation.Node> nodes) {
        return new Observation(1,Instant.now(),route,nodes,false,"COMPLETE","SAFE",null);
    }
    private static class Engine implements UiEngine {
        Observation observation=snapshot("https://org/lightning/page/home",List.of(node("1","Submit")));
        int reads,actions,polls,staleBeforeDispatch; boolean failAction;
        public Observation observe(String scope,Deadline d) { reads++;return observation; }
        public void perform(Action action,Observation o,Deadline d) {
            if(staleBeforeDispatch>0) { staleBeforeDispatch--;throw new Failure("NODE_STALE","Node detached before dispatch"); }
            actions++;if(failAction) throw new Failure("ACTION_COMMIT_UNCERTAIN","Click may have committed");
        }
        public String capabilityVersion() { return "test-engine"; }
        public String route() { return observation.route(); }
        public void open(String route,Deadline d) {}
        public Observation.Node resolve(SemanticTarget t,Observation o) { return o.nodes().getFirst(); }
        public Map<String,String> readVisibleFields(Deadline d) { return Map.of(); }
        public byte[] captureMaskedPng(Deadline d) { return new byte[0]; }
        public void poll(Deadline d) { polls++; }
        public void close() {}
    }
    private LocatorStore map(Engine engine) {
        var store=new LocatorStore(root,"test","read-write");
        store.putAll(List.of(LocatorEntry.mapped("home","submit",engine.observation,engine.observation.nodes().getFirst(),engine.capabilityVersion())));return store;
    }
    private HealingLocators ui(Engine engine,LocatorStore store) {
        var policy=new FrameworkConfig.Locators(root.toString(),"read-write",true,false,false,2,100,5,.90,.10);
        return new HealingLocators(engine,store,policy,deadline,event -> {},(entry,observation,d) -> { throw new AssertionError("No AI allowed"); });
    }
    @Test void dispatchedUncertainActionIsNeverRetried() {
        var engine=new Engine();var store=map(engine);engine.failAction=true;
        assertEquals("ACTION_COMMIT_UNCERTAIN",assertThrows(Failure.class,() -> ui(engine,store).click("home","submit")).code());
        assertEquals(1,engine.actions);assertEquals(1,engine.reads);assertEquals(1,store.get("home","submit").revision());
    }
    @Test void detachedNodeBeforeDispatchIsResolvedAgainWithOnlyOneRealAction() {
        var engine=new Engine();var store=map(engine);engine.staleBeforeDispatch=1;
        ui(engine,store).click("home","submit");assertEquals(2,engine.reads);assertEquals(1,engine.actions);assertEquals(1,engine.polls);
    }
    @Test void differentRouteCannotBeHealed() {
        var engine=new Engine();var store=map(engine);
        engine.observation=snapshot("https://org/lightning/page/other",List.of(node("2","Submit")));
        assertEquals("LOCATOR_WRONG_CONTEXT",assertThrows(Failure.class,() -> ui(engine,store).click("home","submit")).code());assertEquals(0,engine.actions);
    }
    @Test void ambiguousExactMatchIsNotEvadedByHealing() {
        var engine=new Engine();var store=map(engine);
        engine.observation=snapshot(engine.route(),List.of(node("1","Submit"),node("2","Submit")));
        assertEquals("TARGET_AMBIGUOUS",assertThrows(Failure.class,() -> ui(engine,store).click("home","submit")).code());
        assertEquals(3,engine.reads);assertEquals(2,engine.polls);assertEquals(0,engine.actions);
    }
    @Test void missingTargetConsumesOnlyBoundedLocatorRetries() {
        var engine=new Engine();var store=map(engine);engine.observation=snapshot(engine.route(),List.of());
        assertEquals("LOCATOR_LOW_CONFIDENCE",assertThrows(Failure.class,() -> ui(engine,store).click("home","submit")).code());assertEquals(3,engine.reads);assertEquals(0,engine.actions);
    }
    @Test void corruptedStoreAndConcurrentRevisionAreRejected() throws Exception {
        var engine=new Engine();var store=map(engine);var old=store.get("home","submit");
        var update=old.healed(node("2","Send"),snapshot(engine.route(),List.of(node("2","Send"))),"fingerprint");
        store.putAll(List.of(update));
        assertEquals("LOCATOR_CONFLICT",assertThrows(Failure.class,() -> store.putAll(List.of(update))).code());
        String text=Files.readString(store.path());Files.writeString(store.path(),text.replace("Send","Tampered"));
        assertEquals("LOCATOR_INVALID",assertThrows(Failure.class,store::entries).code());
    }
    @Test void incompleteObservationCannotProveOrHealATarget() {
        var engine=new Engine();var store=map(engine);
        engine.observation=new Observation(2,Instant.now(),engine.route(),engine.observation.nodes(),true,"PARTIAL","SAFE","closed root");
        assertEquals("LOCATOR_INCOMPLETE",assertThrows(Failure.class,() -> ui(engine,store).click("home","submit")).code());assertEquals(0,engine.actions);
    }
    @Test void recordRoutesIgnoreRecordIdsAndSecretQueries() {
        assertEquals(LocatorEntry.routeKey("https://org/lightning/r/Account/001000000000001AAA/view?sid=secret"),
            LocatorEntry.routeKey("https://org/lightning/r/Account/001999999999999/view?sid=other#fragment"));
    }
    @Test void screenChangingQueriesStayDistinctWithoutPersistingTheirValues() {
        assertNotEquals(LocatorEntry.routeKey("https://org/app?mode=safe"),LocatorEntry.routeKey("https://org/app?mode=unsafe"));
        assertNotEquals(LocatorEntry.routeKey("https://org/app"),LocatorEntry.routeKey("https://org/app?tab=notes"));
        assertEquals(LocatorEntry.routeKey("https://org/app?tab=notes&mode=safe"),LocatorEntry.routeKey("https://org/app?mode=safe&tab=notes&ts=123"));
        assertEquals(LocatorEntry.routeKey("https://org/app?id=001000000000001AAA&mode=safe"),LocatorEntry.routeKey("https://org/app?id=001999999999999&mode=safe"));
        assertFalse(LocatorEntry.routeKey("https://org/app?search=PRIVATE_SEARCH_VALUE").contains("PRIVATE_SEARCH_VALUE"));
        assertDoesNotThrow(() -> LocatorEntry.routeKey("https://org/app?search=[REDACTED]"));
        var engine=new Engine();engine.observation=snapshot("https://org/app?mode=safe",List.of(node("1","Submit")));var store=map(engine);
        engine.observation=snapshot("https://org/app?mode=unsafe",List.of(node("2","Submit")));
        assertEquals("LOCATOR_WRONG_CONTEXT",assertThrows(Failure.class,() -> ui(engine,store).click("home","submit")).code());assertEquals(0,engine.actions);
    }
    @Test void sameLabelInAnotherFrameOrOnAConflictingControlCannotTakeOverALocator() {
        var engine=new Engine();var store=map(engine);
        for(var attrs:List.of(Map.of("tag","button","testId","submit","frame","other"),Map.of("tag","button","testId","different","frame","main"))) {
            engine.observation=snapshot(engine.route(),List.of(new Observation.Node("2",SemanticTarget.role("button","Submit","page"),"","",false,false,attrs)));
            assertEquals("LOCATOR_LOW_CONFIDENCE",assertThrows(Failure.class,() -> ui(engine,store).click("home","submit")).code());
        }
        assertEquals(0,engine.actions);assertEquals(1,store.get("home","submit").revision());
    }
    @Test void recordParametersStayInMemoryAndCannotHealToAnotherRecord() throws Exception {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");
        var template=new LocatorEntry("home","records.open",LocatorEntry.routeKey(engine.route()),engine.capabilityVersion(),
            SemanticTarget.role("link","${recordName}","page"),Map.of("tag","a","recordLink","true","frame","main"),List.of(),1,List.of());
        store.putAll(List.of(template));byte[] before=Files.readAllBytes(store.path());
        engine.observation=snapshot(engine.route(),List.of(new Observation.Node("1",SemanticTarget.role("link","PRIVATE_RECORD","page"),"","",false,false,Map.of("tag","a","recordLink","true","frame","main"))));
        assertEquals("LOCATOR_PARAMETER_REQUIRED",assertThrows(Failure.class,() -> ui(engine,store).read("home","records.open")).code());
        ui(engine,store).click("home","records.open",Map.of("recordName","PRIVATE_RECORD"));assertEquals(1,engine.actions);
        assertEquals("LOCATOR_STALE",assertThrows(Failure.class,() -> ui(engine,store).click("home","records.open",Map.of("recordName","DIFFERENT_RECORD"))).code());
        assertEquals(1,engine.actions);assertArrayEquals(before,Files.readAllBytes(store.path()));
        engine.observation=snapshot(engine.route(),List.of(new Observation.Node("other-frame",SemanticTarget.role("link","PRIVATE_RECORD","page"),"","",false,false,Map.of("recordLink","true","frame","other"))));
        assertEquals("LOCATOR_STALE",assertThrows(Failure.class,() -> ui(engine,store).read("home","records.open",Map.of("recordName","PRIVATE_RECORD"))).code());
    }
    @Test void fullRemapArchivesRemovedScopeKeysAndRejectsConcurrentChanges() throws Exception {
        var engine=new Engine();var store=map(engine);var before=store.entries();
        var dialog=new LocatorEntry("home","save-dialog",LocatorEntry.routeKey(engine.route()),engine.capabilityVersion(),SemanticTarget.role("button","Save","dialog"),Map.of(),List.of(),1,List.of());
        store.putAll(List.of(dialog));before=store.entries();
        store.replaceScope("home","page",List.of(),before);
        assertEquals(Set.of("home/save-dialog"),store.entries().keySet());
        var archive=root.resolve("history").resolve(store.path().getFileName().toString()).resolve(Json.hash(before)+".json");
        assertTrue(Files.exists(archive));assertEquals(2,Json.read(archive,LocatorStore.Document.class).entries().size());
        var stale=before;
        assertEquals("LOCATOR_CONFLICT",assertThrows(Failure.class,() -> store.replaceScope("home","page",List.of(),stale)).code());
    }
}
