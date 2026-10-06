package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UiMapperTest {
    @TempDir Path root;
    private final Deadline deadline=Deadline.after(Duration.ofSeconds(10));
    private static class Engine implements UiEngine {
        List<Observation.Node> nodes=List.of(new Observation.Node("1",new SemanticTarget("textbox","Company","Company","page"),"BUSINESS_PRIVATE_VALUE","BUSINESS_PRIVATE_VALUE",false,false,Map.of("tag","input","apiName","Company__c")),
            new Observation.Node("2",SemanticTarget.role("button","Save","page"),"Save","",false,false,Map.of("tag","button")));
        int actions;
        public Observation observe(String scope,Deadline d) { return new Observation(1,Instant.now(),"https://org/lightning/page/home?sid=SECRET",nodes,false,"COMPLETE","SAFE",null); }
        public String capabilityVersion() { return "test-engine"; }
        public String route() { return "https://org/lightning/page/home"; }
        public void open(String route,Deadline d) { throw new AssertionError("Mapper must not navigate"); }
        public void perform(Action a,Observation o,Deadline d) { actions++;throw new AssertionError("Mapper must not act"); }
        public Observation.Node resolve(SemanticTarget t,Observation o) { return o.nodes().getFirst(); }
        public Map<String,String> readVisibleFields(Deadline d) { return Map.of(); }
        public byte[] captureMaskedPng(Deadline d) { return new byte[0]; }
        public void poll(Deadline d) {}
        public void close() {}
    }
    private ModelClient.Response response(Object data) {
        return new ModelClient.Response("finish",null,null,null,"PASSED","Mapped current controls",null,List.of("1","2"),Json.MAPPER.valueToTree(data),10L);
    }
    private Object targets() { return Map.of("targets",List.of(Map.of("key","company","nodeId","1"),Map.of("key","save","nodeId","2"))); }
    private UiMapper mapper(Engine engine,LocatorStore store,ModelClient model) {
        return new UiMapper(engine,store,model,FrameworkConfig.defaults().agent(),new SecretRegistry(),event -> {});
    }
    @Test void mapsValidatedReferencesThenRunsWithoutModelAndDoesNotStoreValuesOrNodeIds() throws Exception {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");
        var model=new ScriptedModelClient(request -> {
            assertEquals("map",request.kind());assertFalse(Json.string(request).contains("BUSINESS_PRIVATE_VALUE"));
            assertFalse(request.observation().route().contains("SECRET"));return response(targets());
        });
        mapper(engine,store,model).map("details","page",deadline);
        assertEquals(1,model.calls());assertEquals(2,store.entries().size());assertEquals(0,engine.actions);
        String saved=Files.readString(store.path());assertFalse(saved.contains("BUSINESS_PRIVATE_VALUE"));assertFalse(saved.contains("nodeId"));assertFalse(saved.contains("SECRET"));
        var ui=new HealingLocators(engine,store,FrameworkConfig.defaultLocators(),deadline,event -> {},(entry,observation,d) -> {throw new AssertionError("AI must not run");});
        assertEquals("BUSINESS_PRIVATE_VALUE",ui.read("details","company").value());assertEquals(1,model.calls());
    }
    @Test void unchangedRemappingDoesNotRewriteRepository() throws Exception {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");
        var mapper=mapper(engine,store,new ScriptedModelClient(request -> response(targets())));
        mapper.map("details","page",deadline);byte[] before=Files.readAllBytes(store.path());
        mapper.map("details","page",deadline);assertArrayEquals(before,Files.readAllBytes(store.path()));
        assertEquals(1,store.get("details","company").revision());
    }
    @Test void fullyUnchangedStoredScopeReusesWithoutAIAndDriftRequiresNewMapping() throws Exception {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");var provider=new ScriptedModelClient(request -> response(targets()));
        var client=new AiWorkflowClient(FrameworkConfig.defaults().agent(),() -> provider,() -> provider,e -> {});
        var mapper=mapper(engine,store,client);mapper.map("details","page",deadline);byte[] original=Files.readAllBytes(store.path());
        mapper.map("details","page",deadline);assertEquals(1,provider.calls());assertArrayEquals(original,Files.readAllBytes(store.path()));
        var field=engine.nodes.getFirst();engine.nodes=List.of(new Observation.Node("1",new SemanticTarget("textbox","Updated company","Updated company","page"),"","",false,false,field.attributes()),engine.nodes.get(1));
        mapper.map("details","page",deadline);assertEquals(2,provider.calls());assertEquals("Updated company",store.get("details","company").primary().name());
    }
    @Test void invalidNodeReferenceOrOmittedControlCannotWriteAPartialMapping() {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");
        var mapper=mapper(engine,store,new ScriptedModelClient(request -> response(Map.of("targets",List.of(Map.of("key","company","nodeId","invented"))))));
        assertEquals("MODEL_OUTPUT_INVALID",assertThrows(Failure.class,() -> mapper.map("details","page",deadline)).code());assertFalse(Files.exists(store.path()));
        var omitted=mapper(engine,store,new ScriptedModelClient(request -> response(Map.of("targets",List.of(Map.of("key","company","nodeId","1"))))));
        assertEquals("MODEL_OUTPUT_INVALID",assertThrows(Failure.class,() -> omitted.map("details","page",deadline)).code());assertFalse(Files.exists(store.path()));
    }
    @Test void actionRequestFromModelIsRejectedWithBoundedRepairCall() {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");
        var model=new ScriptedModelClient(request -> new ModelClient.Response("click","2",null,null,null,null,null,List.of(),null,10L));
        assertEquals("MODEL_OUTPUT_INVALID",assertThrows(Failure.class,() -> mapper(engine,store,model).map("details","page",deadline)).code());
        assertEquals(2,model.calls());assertEquals(0,engine.actions);assertFalse(Files.exists(store.path()));
    }
    @Test void changedUiDuringDiscoveryCannotPersistOutdatedMapping() {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");
        var model=new ScriptedModelClient(request -> { engine.nodes=List.of();return response(targets()); });
        assertEquals("MAPPING_UI_CHANGED",assertThrows(Failure.class,() -> mapper(engine,store,model).map("details","page",deadline)).code());assertFalse(Files.exists(store.path()));
    }
    @Test void validBlockedDiscoveryNeverTriggersTheCallersFormatRetry() {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");
        var provider=new ScriptedModelClient(request -> new ModelClient.Response("finish",null,null,null,"BLOCKED","Insufficient evidence","AMBIGUOUS",List.of(),null,10L));
        var client=new AiWorkflowClient(FrameworkConfig.defaults().agent(),() -> provider,() -> {throw new AssertionError();},e -> {});
        assertEquals("MAPPING_BLOCKED",assertThrows(Failure.class,() -> mapper(engine,store,client).map("details","page",deadline)).code());
        assertEquals(1,provider.calls());assertEquals(0,engine.actions);assertFalse(Files.exists(store.path()));
    }
    @Test void newlyAppearedControlDuringDiscoveryRequiresRemappingInsteadOfPartialCoverage() {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");
        var model=new ScriptedModelClient(request -> {
            var nodes=new ArrayList<>(engine.nodes);
            nodes.add(new Observation.Node("3",SemanticTarget.role("button","Refresh","page"),"Refresh","",false,false));engine.nodes=nodes;
            return response(targets());
        });
        assertEquals("MAPPING_UI_CHANGED",assertThrows(Failure.class,() -> mapper(engine,store,model).map("details","page",deadline)).code());assertFalse(Files.exists(store.path()));
    }
    @Test void salesforceDecorativeNodesWithoutAriaRolesAreSafelySkipped() {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");
        var nodes=new ArrayList<>(engine.nodes);
        nodes.add(new Observation.Node("decorative",new SemanticTarget(null,"Company label",null,"page"),"Company label","",false,false));
        engine.nodes=nodes;
        mapper(engine,store,new ScriptedModelClient(request -> response(targets()))).map("details","page",deadline);
        assertEquals(2,store.entries().size());
    }
    @Test void virtualizedRecordRowsAreExcludedAndReplacedByOneParameterTemplate() throws Exception {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");
        var nodes=new ArrayList<>(engine.nodes);
        var record=new Observation.Node("record",SemanticTarget.role("link","PRIVATE_RECORD_NAME","page"),"private","",false,false,Map.of("tag","a","recordLink","true","recordRow","true","frame","main"));
        nodes.add(record);nodes.add(new Observation.Node("row-check",SemanticTarget.role("checkbox","Select PRIVATE_RECORD_NAME","page"),"","",false,false,Map.of("recordRow","true")));
        engine.nodes=nodes;store.putAll(List.of(LocatorEntry.mapped("details","row-1.name",engine.observe("page",deadline),record,engine.capabilityVersion())));
        mapper(engine,store,new ScriptedModelClient(request -> {
            assertFalse(Json.string(request.observation()).contains("PRIVATE_RECORD_NAME"));return response(targets());
        })).map("details","page",deadline);
        assertEquals(3,store.entries().size());assertTrue(store.get("details","records.open").parameterized());
        assertFalse(Files.readString(store.path()).contains("PRIVATE_RECORD_NAME"));
    }
    @Test void malformedProviderJsonGetsOneRepairCallButTransportFailuresDoNotRetry() {
        var engine=new Engine();var store=new LocatorStore(root,"test","read-write");int[] calls={0};
        var model=new ScriptedModelClient(request -> {
            if(calls[0]++==0) throw new Failure("MODEL_OUTPUT_INVALID","Malformed JSON");
            assertTrue(request.instruction().contains("without Markdown"));return response(targets());
        });
        mapper(engine,store,model).map("details","page",deadline);assertEquals(2,model.calls());assertEquals(2,store.entries().size());
        var unavailable=new ScriptedModelClient(request -> {throw new Failure("MODEL_UNAVAILABLE","Transport failed");});
        assertEquals("MODEL_UNAVAILABLE",assertThrows(Failure.class,() -> mapper(engine,store,unavailable).map("details","page",deadline)).code());assertEquals(1,unavailable.calls());
    }
}
