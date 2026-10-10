package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CaseTargetReuseTest {
    @TempDir Path root;
    private static class Engine implements UiEngine {
        List<Observation.Node> nodes=List.of(node("Name","Name__c","main"));
        String route="https://fixture/app",capability="fixture";String coverage="COMPLETE";int actions,observations;
        Runnable nextObservation=() -> {};
        public Observation observe(String scope,Deadline deadline) {
            observations++;nextObservation.run();
            var fresh=nodes.stream().map(node -> new Observation.Node(observations+"-"+node.id(),node.target(),node.text(),node.value(),node.disabled(),node.checked(),node.attributes())).toList();
            return new Observation(observations,Instant.now(),route,fresh,false,coverage,"SAFE",null);
        }
        public String capabilityVersion() {return capability;}
        public String route() {return route;}
        public void open(String route,Deadline deadline) {}
        public void perform(Action action,Observation observation,Deadline deadline) {
            actions++;
            if(action.kind().equals("fill")) nodes=nodes.stream().map(node -> new Observation.Node(node.id(),node.target(),node.text(),action.value(),node.disabled(),node.checked(),node.attributes())).toList();
        }
        public Observation.Node resolve(SemanticTarget target,Observation observation) {return observation.nodes().getFirst();}
        public Map<String,String> readVisibleFields(Deadline deadline) {return Map.of();}
        public byte[] captureMaskedPng(Deadline deadline) {return new byte[0];}
        public void poll(Deadline deadline) {deadline.check();}
        public void close() {}
    }
    static Observation.Node node(String name,String api,String frame) {
        return new Observation.Node(name,new SemanticTarget("textbox",name,name,"page"),"","",false,false,Map.of("tag","input","apiName",api,"frame",frame));
    }
    private LocatorStore store() {return new LocatorStore(root,"isolated","read-write");}
    private Deadline deadline() {return Deadline.after(Duration.ofSeconds(8));}
    private CaseSpec spec(String instruction) {return new CaseSpec("case","/app",List.of(new CaseSpec.Step("expectVisible",instruction,null,null,null,null)));}
    private ScriptedModelClient model() {return new ScriptedModelClient(request -> response(Map.of("nodeId",request.observation().nodes().getFirst().id())));}
    private ModelClient.Response response(Object data) {return new ModelClient.Response("finish",null,null,null,"PASSED","Fixture response",null,List.of(),Json.MAPPER.valueToTree(data),10L);}
    private FrameworkConfig config() {
        var c=FrameworkConfig.defaults();return new FrameworkConfig(1,c.projectId(),c.profile(),c.org(),c.ui(),c.auth(),c.personas(),new FrameworkConfig.Execution(1,0,20,1,1,false),c.agent(),c.cache(),c.retention(),c.evidence(),c.report(),c.locators());
    }
    private CaseLearner learner(Engine engine,LocatorStore store,ModelClient model) {return new CaseLearner(engine,store,model,config(),new SecretRegistry(),event -> {});}
    private ModelClient unavailable() {return new ScriptedModelClient(request -> {throw new AssertionError("Known controls must not request a model");});}
    private LocatorEntry seed(Engine engine,LocatorStore store) {
        var observation=engine.observe("page",deadline());var entry=LocatorEntry.mapped("shared","name",observation,observation.nodes().getFirst(),engine.capabilityVersion());store.putAll(List.of(entry));return entry;
    }
    @Test void freshLearnerAndDiskRestartReuseFreeFormIntentWithZeroCallsAndNoLocatorRewrite() throws Exception {
        var engine=new Engine();var store=store();var provider=model();String instruction="The field labelled Name";
        var first=learner(engine,store,provider).learn(spec(instruction),"isolated",Map.of(),deadline());assertEquals(1,provider.calls());
        byte[] original=Files.readAllBytes(store.path());
        var second=learner(new Engine(),store(),unavailable()).learn(spec(instruction),"isolated",Map.of(),deadline());
        assertEquals(first.steps(),second.steps());assertArrayEquals(original,Files.readAllBytes(store.path()));assertEquals(1,store.entries().size());
        assertFalse(Files.readString(store.path().resolveSibling(store.path().getFileName()+".target-index.json")).contains(instruction));
    }
    @Test void existingMappedSemanticNameApiNameAndTestIdNeedNoModel() {
        var engine=new Engine();var store=store();var entry=seed(engine,store);
        for(String instruction:List.of("Name","Name__c")) {
            var recipe=learner(new Engine(),store(),unavailable()).learn(spec(instruction),"isolated",Map.of(),deadline());
            assertEquals(entry.page(),recipe.steps().getFirst().page());assertEquals(entry.key(),recipe.steps().getFirst().key());
        }
        var testIdNode=new Observation.Node("Name",engine.nodes.getFirst().target(),"","",false,false,Map.of("tag","input","testId","name-test","frame","main"));
        engine.nodes=List.of(testIdNode);var testStore=new LocatorStore(root.resolve("testid"),"isolated","read-write");seed(engine,testStore);
        learner(engine,testStore,unavailable()).learn(spec("name-test"),"isolated",Map.of(),deadline());assertEquals(1,testStore.entries().size());
    }
    @Test void newParaphraseIsResolvedOnceButDoesNotCreateAnotherDefinition() throws Exception {
        var engine=new Engine();var store=store();var original=seed(engine,store);byte[] bytes=Files.readAllBytes(store.path());var provider=model();
        var recipe=learner(engine,store,provider).learn(spec("The requested textbox"),"isolated",Map.of(),deadline());
        assertEquals(1,provider.calls());assertEquals(original.key(),recipe.steps().getFirst().key());assertArrayEquals(bytes,Files.readAllBytes(store.path()));
        learner(new Engine(),store(),unavailable()).learn(spec("The requested textbox"),"isolated",Map.of(),deadline());assertEquals(1,store.entries().size());
    }
    @Test void changedStableAnchorCreatesIsolatedDefinitionAndPreservesOldRecipeTarget() {
        var engine=new Engine();var store=store();var original=seed(engine,store);engine.nodes=List.of(node("Name","Replacement__c","main"));var provider=model();
        var recipe=learner(engine,store,provider).learn(spec("Name"),"isolated",Map.of(),deadline());
        assertEquals(1,provider.calls());assertNotEquals(original.page(),recipe.steps().getFirst().page());assertEquals(original,store.get(original.page(),original.key()));assertEquals(2,store.entries().size());
    }
    @Test void missingSavedAnchorCannotAuthorizeReuseOrDuplicateOldIdentity() {
        var engine=new Engine();var store=store();seed(engine,store);engine.nodes=List.of(new Observation.Node("Name",engine.nodes.getFirst().target(),"","",false,false,Map.of("tag","input","frame","main")));var provider=model();
        learner(engine,store,provider).learn(spec("Name"),"isolated",Map.of(),deadline());assertEquals(1,provider.calls());assertEquals(2,store.entries().size());
    }
    @Test void routeQueryOriginCapabilityFrameAndScopeChangesCannotReuseOldControls() {
        int iteration=0;
        for(String change:List.of("origin","query","capability","frame","scope")) {
            var engine=new Engine();var store=new LocatorStore(root.resolve("boundary-"+(iteration++)),"isolated","read-write");var original=seed(engine,store);
            switch(change) {
                case "origin" -> engine.route="https://different.example/app";
                case "query" -> engine.route="https://fixture/app?view=other";
                case "capability" -> engine.capability="new-capability";
                case "frame" -> engine.nodes=List.of(node("Name","Name__c","child"));
                case "scope" -> engine.nodes=List.of(new Observation.Node("Name",new SemanticTarget("textbox","Name","Name","dialog:Other"),"","",false,false,engine.nodes.getFirst().attributes()));
            }
            var supplied=change.equals("scope")?new CaseSpec("case","/app",List.of(new CaseSpec.Step("expectVisible","Name","dialog:Other",null,null,null))):spec("Name");
            var provider=model();learner(engine,store,provider).learn(supplied,"isolated",Map.of(),deadline());
            assertEquals(1,provider.calls(),change);assertEquals(2,store.entries().size(),change);assertEquals(original,store.get(original.page(),original.key()));
        }
    }
    @Test void incompleteOrAmbiguousCurrentEvidenceCannotDispatchOrPublishReuse() {
        for(boolean incomplete:List.of(true,false)) {
            var engine=new Engine();var store=new LocatorStore(root.resolve("evidence-"+incomplete),"isolated","read-write");seed(engine,store);var original=store.entries();
            if(incomplete) engine.coverage="PARTIAL";else engine.nodes=List.of(node("Name","Name__c","main"),node("Name","Other__c","child"));
            var provider=model();assertEquals(incomplete?"LOCATOR_INCOMPLETE":"TARGET_AMBIGUOUS",assertThrows(Failure.class,() -> learner(engine,store,provider).learn(spec("Name"),"isolated",Map.of(),deadline())).code());
            assertEquals(incomplete?0:1,provider.calls());assertEquals(0,engine.actions);assertEquals(original,store.entries());
        }
    }
    @Test void changedSecondObservationFailsBeforeModelOrMutation() {
        var engine=new Engine();var store=store();seed(engine,store);engine.observations=0;
        engine.nextObservation=() -> {if(engine.observations==2) engine.nodes=List.of(node("Name","Replacement__c","main"));};
        assertEquals("MAPPING_UI_CHANGED",assertThrows(Failure.class,() -> learner(engine,store,unavailable()).learn(spec("Name"),"isolated",Map.of(),deadline())).code());assertEquals(0,engine.actions);assertEquals(1,store.entries().size());
    }
    @Test void competingIntentAssociationsRequireDisambiguationWithoutDuplicatingDefinitions() {
        var engine=new Engine();var store=store();var first=seed(engine,store);engine.nodes=List.of(node("Other","Other__c","main"));
        var observation=engine.observe("page",deadline());var second=LocatorEntry.mapped("shared","other",observation,observation.nodes().getFirst(),engine.capabilityVersion());store.putAll(List.of(second));
        var index=new CaseTargetIndex(store);var intent=spec("Requested field").steps().getFirst();index.remember(intent,first);index.remember(intent,second);
        engine.nodes=List.of(node("Name","Name__c","main"),node("Other","Other__c","main"));var provider=model();
        var recipe=learner(engine,store,provider).learn(spec("Requested field"),"isolated",Map.of(),deadline());
        assertEquals(1,provider.calls());assertEquals(first.key(),recipe.steps().getFirst().key());assertEquals(2,store.entries().size());
    }
    @Test void corruptedAssociationBytesAreRejectedEvenWithRestoredSizeAndTimestamp() throws Exception {
        var engine=new Engine();var store=store();var entry=seed(engine,store);var intent=spec("Requested field").steps().getFirst();var index=new CaseTargetIndex(store);index.remember(intent,entry);
        assertEquals(1,index.references(intent,entry.route(),entry.engineVersion()).size());Path file=store.path().resolveSibling(store.path().getFileName()+".target-index.json");
        var modified=Files.getLastModifiedTime(file);String content=Files.readString(file);int checksum=content.indexOf("\"checksum\":\"")+12;
        char replacement=content.charAt(checksum)=='a'?'b':'a';Files.writeString(file,content.substring(0,checksum)+replacement+content.substring(checksum+1));Files.setLastModifiedTime(file,modified);
        assertEquals("CASE_TARGET_INDEX_INVALID",assertThrows(Failure.class,() -> index.references(intent,entry.route(),entry.engineVersion())).code());
        assertEquals("CASE_TARGET_INDEX_INVALID",assertThrows(Failure.class,() -> learner(engine,store,unavailable()).learn(spec("Name"),"isolated",Map.of(),deadline())).code());assertEquals(0,engine.actions);
    }
    @Test void recordTemplatesReuseAcrossNamesAndReferenceNamesWithoutPersistingBusinessValues() throws Exception {
        var engine=new Engine();engine.nodes=List.of(new Observation.Node("record",SemanticTarget.role("link","PRIVATE_FIRST_RECORD","page"),"","",false,false,Map.of("tag","a","frame","main","recordLink","true","recordRow","true","group","PRIVATE_FIRST_RECORD")));
        var firstSpec=new CaseSpec("records","/app",List.of(new CaseSpec.Step("expectVisible","Requested record",null,null,null,"record")));var store=store();var first=learner(engine,store,model()).learn(firstSpec,"isolated",Map.of("record","PRIVATE_FIRST_RECORD"),deadline());
        engine.nodes=List.of(new Observation.Node("other-record",SemanticTarget.role("link","PRIVATE_SECOND_RECORD","page"),"","",false,false,Map.of("tag","a","frame","main","recordLink","true","recordRow","true","group","PRIVATE_SECOND_RECORD")));
        var secondSpec=new CaseSpec("records","/app",List.of(new CaseSpec.Step("expectVisible","Requested record",null,null,null,"otherRecord")));
        var second=learner(engine,store(),unavailable()).learn(secondSpec,"isolated",Map.of("otherRecord","PRIVATE_SECOND_RECORD"),deadline());
        assertEquals(first.steps().getFirst().page(),second.steps().getFirst().page());assertEquals(1,store.entries().size());
        for(Path file:List.of(store.path(),store.path().resolveSibling(store.path().getFileName()+".target-index.json"))) assertFalse(Files.readString(file).contains("PRIVATE_"));
    }
    @Test void newCaseReusesDefinitionsButWrongExpectationStillFailsAfterExactlyOneFill() {
        var engine=new Engine();var store=store();seed(engine,store);
        var supplied=new CaseSpec("wrong-result","/app",List.of(new CaseSpec.Step("fill","Name",null,null,"input",null),new CaseSpec.Step("expectValue","Name",null,null,"expected",null)));
        assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> learner(engine,store,unavailable()).learn(supplied,"isolated",Map.of("input","actual","expected","wrong"),deadline())).code());
        assertEquals(1,engine.actions);assertEquals(1,store.entries().size());
    }
    @Test void historicalKnowledgeAloneCannotJustifyFirstStepAbsence() {
        var engine=new Engine();var store=store();seed(engine,store);engine.nodes=List.of();
        var supplied=new CaseSpec("absence","/app",List.of(new CaseSpec.Step("expectHidden","Name",null,null,null,null)));
        assertEquals("NEGATIVE_ASSERTION_UNPROVABLE",assertThrows(Failure.class,() -> learner(engine,store,unavailable()).learn(supplied,"isolated",Map.of(),deadline())).code());
    }
    @Test void duplicateHistoricalDefinitionsAreReusedDeterministicallyWithoutDeletingOldKeys() throws Exception {
        var engine=new Engine();var store=store();var first=seed(engine,store);
        store.putAll(List.of(new LocatorEntry("older","name",first.route(),first.engineVersion(),first.primary(),first.attributes(),first.alternatives(),1,List.of())));byte[] original=Files.readAllBytes(store.path());
        var recipe=learner(engine,store,unavailable()).learn(spec("Name"),"isolated",Map.of(),deadline());
        assertEquals("older",recipe.steps().getFirst().page());assertEquals(2,store.entries().size());assertArrayEquals(original,Files.readAllBytes(store.path()));
    }
    @Test void explicitCountCanReuseAGroupWhileTheExpectedCountStaysIndependent() {
        var engine=new Engine();var store=store();seed(engine,store);engine.nodes=List.of(node("Name","Name__c","main"),node("Name","Name__c","main"));
        var supplied=new CaseSpec("count","/app",List.of(new CaseSpec.Step("expectCount","Name",null,null,"count",null)));
        learner(engine,store,unavailable()).learn(supplied,"isolated",Map.of("count","2"),deadline());
        assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> learner(engine,store,unavailable()).learn(supplied,"isolated",Map.of("count","1"),deadline())).code());assertEquals(1,store.entries().size());
    }
}
