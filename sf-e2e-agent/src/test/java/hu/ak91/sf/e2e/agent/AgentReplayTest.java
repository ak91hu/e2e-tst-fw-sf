package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.replay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentReplayTest {
    @TempDir Path root;
    static final String ID="006000000000001",ID2="006000000000002";
    private FrameworkConfig config(String mode,boolean strict) {
        var base=FrameworkConfig.defaults();
        return new FrameworkConfig(1,base.projectId(),"ai-cache",base.org(),base.ui(),base.auth(),Map.of("sales",new FrameworkConfig.PersonaConfig("TEST_USER","TEST_STATE")),base.execution(),base.agent(),
            new FrameworkConfig.Cache(mode,strict,false,root.resolve("cache").toString(),"fail"),base.retention(),base.evidence(),base.report());
    }
    @Test void customAgentRoutesAndLocalizedChoiceFieldsUseSemanticPolicyInsteadOfEnglishBusinessLabels() {
        var scope=new ActScope("attempt","package__Widget__c",ID,Set.of("Állapot","Megnevezés"),"region:Adatok","/s/detail/"+ID+"/edit");
        var name=new Observation.Node("name",new SemanticTarget("textbox","Megnevezés","Megnevezés","region:Adatok"),"","",false,false);
        var observation=new Observation(1,Instant.now(),"https://portal.example/s/detail/"+ID+"/edit",List.of(name),false,"COMPLETE","SAFE",null);
        assertDoesNotThrow(() -> scope.validate(observation,name,"fill","Új érték",Map.of("name",StepParam.unique("Új érték"))));
        var choice=new Observation.Node("choice",new SemanticTarget("combobox","Állapot","Állapot","region:Adatok"),"","",false,false);
        assertThrows(Failure.class,() -> scope.validate(observation,choice,"select","Aktív",Map.of("state",StepParam.unique("Aktív"))));
        var other=new Observation(2,Instant.now(),"https://portal.example/s/detail/"+ID2+"/edit",List.of(name),false,"COMPLETE","SAFE",null);
        assertThrows(Failure.class,() -> scope.validate(other,name,"fill","Új érték",Map.of("name",StepParam.unique("Új érték"))));
    }
    static final class Engine implements UiEngine {
        String id=ID,value="Before";long revision;int mutations;boolean ambiguous,stale;
        public String route() { return "https://org.develop.my.salesforce.com/lightning/r/Opportunity/"+id+"/edit?recordTypeId=type"; }
        public void open(String route,Deadline deadline) { throw new AssertionError("Agent must not navigate"); }
        public Observation observe(String container,Deadline deadline) {
            deadline.check();var target=new SemanticTarget("textbox","Opportunity Name","Opportunity Name",container);
            List<Observation.Node> nodes=new ArrayList<>(List.of(new Observation.Node(++revision+"-name",target,"",value,false,false)));
            if(ambiguous) nodes.add(new Observation.Node(revision+"-other",target,"",value,false,false));
            nodes.add(new Observation.Node(revision+"-save",SemanticTarget.role("button","Save",container),"Save","",false,false));
            return new Observation(revision,Instant.now(),route(),nodes,false,"COMPLETE","SAFE",null);
        }
        public Observation.Node resolve(SemanticTarget target,Observation observation) {
            var nodes=observation.nodes().stream().filter(n -> n.target().equals(target)).toList();
            Failure.require(nodes.size()==1,nodes.isEmpty()?"TARGET_NOT_FOUND":"TARGET_AMBIGUOUS","Ambiguous target");return nodes.getFirst();
        }
        public void perform(Action action,Observation observation,Deadline deadline) {
            deadline.check();Failure.require(observation.revision()==revision && !stale,"NODE_STALE","Stale target");
            if(action.kind().equals("fill")) { value=action.value();mutations++;revision++; } else throw new Failure("POLICY_DENIED","Only fill supported");
        }
        public Map<String,String> readVisibleFields(Deadline deadline) { return Map.of("Opportunity Name",value); }
        public byte[] captureMaskedPng(Deadline deadline) { throw new UnsupportedOperationException(); }
        public void poll(Deadline deadline) { deadline.check(); }
        public String capabilityVersion() { return "fake-v1"; }
        public void close() {}
    }
    private ModelClient.Response finish(ModelClient.Request request,String verdict,String error) {
        return new ModelClient.Response("finish",null,null,null,verdict,"Current UI evidence",error,List.of(request.observation().nodes().getFirst().id()),null,1L);
    }
    private ScriptedModelClient script() {
        return new ScriptedModelClient(request -> {
            var field=request.observation().nodes().getFirst();
            if(!field.value().equals(String.valueOf(request.params().get("name"))))
                return new ModelClient.Response("fill",field.id(),"name",null,null,null,null,null,null,1L);
            return finish(request,"PASSED",null);
        });
    }
    private SfAgent agent(Engine engine,ModelClient model,FrameworkConfig config,String attempt) {
        return new SfAgent(engine,() -> model,config,Persona.SALES,"SF-AI-001",attempt,Deadline.after(Duration.ofSeconds(2)),e -> {});
    }
    private ActScope scope(String attempt,String id) { return new ActScope(attempt,"Opportunity",id,Set.of("Opportunity Name"),"edit-dialog"); }
    private SfAgent.ActResult act(SfAgent agent,String attempt,String id,String name) {
        return agent.act("edit-fields","Set Opportunity Name to {name}.",Map.of("name",StepParam.unique(name)),scope(attempt,id));
    }
    private UiVerification proof(String attempt,String id,String name) {
        return UiVerification.compare(attempt,id,Map.of("Opportunity Name",name),Map.of("Opportunity Name",name),"route","current-evidence");
    }
    @Test void cacheWritesOnlyAfterCurrentPersistedProofAndRebindsUniqueValues() throws Exception {
        Engine first=new Engine();var scripted=script();var firstAgent=agent(first,scripted,config("read-write",false),"attempt-one");
        var act=act(firstAgent,"attempt-one",ID,"Unique-first");
        assertTrue(new CacheStore(root.resolve("cache")).list().isEmpty());
        assertThrows(Failure.class,firstAgent::requireProofs);
        firstAgent.confirm(act,proof("attempt-one",ID,"Unique-first"));assertEquals(1,new CacheStore(root.resolve("cache")).list().size());
        String entry=Files.readString(root.resolve("cache").resolve(new CacheStore(root.resolve("cache")).list().getFirst()));
        assertFalse(entry.contains("Unique-first"));assertFalse(entry.contains(ID));
        Engine second=new Engine();second.id=ID2;
        var replayModel=new ScriptedModelClient(request -> { throw new AssertionError("Replay must make zero model calls"); });
        var replayAgent=agent(second,replayModel,config("read-only",false),"attempt-two");
        var replay=act(replayAgent,"attempt-two",ID2,"Unique-second");
        assertEquals("REPLAYED",replay.cacheStatus);assertEquals("Unique-second",second.value);assertEquals(0,replayModel.calls());
        replayAgent.confirm(replay,proof("attempt-two",ID2,"Unique-second"));
    }
    @Test void unrelatedAttemptOrWrongExpectedValueCannotAuthorizeCache() {
        var engine=new Engine();var agent=agent(engine,script(),config("read-write",false),"attempt");
        var result=act(agent,"attempt",ID,"Unique");
        assertThrows(Failure.class,() -> agent.confirm(result,proof("another",ID,"Unique")));
        assertThrows(Failure.class,() -> agent.confirm(result,proof("attempt",ID,"Different")));
        assertTrue(new CacheStore(root.resolve("cache")).list().isEmpty());
    }
    @Test void strictReplayDoesNotCallModelWhenTargetIsAmbiguous() {
        var first=new Engine();var agent=agent(first,script(),config("read-write",false),"first");
        var act=act(agent,"first",ID,"Unique-first");agent.confirm(act,proof("first",ID,"Unique-first"));
        var second=new Engine();second.ambiguous=true;
        var model=new ScriptedModelClient(request -> { throw new AssertionError("Strict stale trace must not call model"); });
        var strict=agent(second,model,config("read-only",true),"second");
        assertEquals("REPLAY_STALE",assertThrows(Failure.class,() -> act(strict,"second",ID,"Unique-second")).code());
        assertEquals(0,second.mutations);assertEquals(0,model.calls());
    }
    @Test void invalidIntegrityCannotDriveBrowser() throws Exception {
        var first=new Engine();var agent=agent(first,script(),config("read-write",false),"first");
        var result=act(agent,"first",ID,"Unique");agent.confirm(result,proof("first",ID,"Unique"));
        var path=root.resolve("cache").resolve(new CacheStore(root.resolve("cache")).list().getFirst());
        Files.writeString(path,Files.readString(path).replace("fill","click"));
        var second=new Engine();var other=agent(second,script(),config("read-only",false),"second");
        assertThrows(Failure.class,() -> act(other,"second",ID,"New"));assertEquals(0,second.mutations);
    }
    @Test void maliciousSaveRequestFailsPolicyBeforeDispatch() {
        var engine=new Engine();
        var model=new ScriptedModelClient(request -> new ModelClient.Response("click",request.observation().nodes().getLast().id(),null,"Save",null,null,null,null,null,1L));
        var agent=agent(engine,model,config("off",false),"attempt");
        assertThrows(Failure.class,() -> act(agent,"attempt",ID,"Unique"));assertEquals(0,engine.mutations);
    }
    @Test void changingRecordRouteCannotExpandActScope() {
        var engine=new Engine();engine.id=ID2;var agent=agent(engine,script(),config("off",false),"attempt");
        assertEquals("RECORD_SCOPE_VIOLATION",assertThrows(Failure.class,() -> act(agent,"attempt",ID,"Unique")).code());assertEquals(0,engine.mutations);
    }
    @Test void contradictorySuccessGetsOnlyOneRepairAndNeverPasses() {
        var engine=new Engine();var model=new ScriptedModelClient(request -> finish(request,"PASSED","ERROR"));
        var agent=agent(engine,model,config("off",false),"attempt");
        assertEquals("MODEL_OUTPUT_INVALID",assertThrows(Failure.class,() -> act(agent,"attempt",ID,"Unique")).code());assertEquals(2,model.calls());
    }
    @Test void noOpActCannotBecomeReplayRecording() {
        var engine=new Engine();var model=new ScriptedModelClient(request -> finish(request,"PASSED",null));
        var agent=agent(engine,model,config("read-write",false),"attempt");
        assertEquals("END_MISMATCH",assertThrows(Failure.class,() -> act(agent,"attempt",ID,"Unique")).code());
        assertTrue(new CacheStore(root.resolve("cache")).list().isEmpty());
    }
    @Test void independentJudgmentsHaveNoActingLedgerAndCannotMutate() {
        var engine=new Engine();var model=new ScriptedModelClient(request -> {
            assertEquals("assert",request.kind());assertTrue(request.ledger().isEmpty());return finish(request,"PASSED",null);
        });
        agent(engine,model,config("read-write",false),"attempt").assertThat("The name is visible");
        assertEquals(1,model.calls());assertEquals(0,engine.mutations);assertTrue(new CacheStore(root.resolve("cache")).list().isEmpty());
    }
    @Test void extractionRejectsUnexpectedOrMissingFields() {
        var engine=new Engine();var model=new ScriptedModelClient(request -> new ModelClient.Response("finish",null,null,null,"PASSED","Current UI",null,List.of(request.observation().nodes().getFirst().id()),Json.MAPPER.valueToTree(Map.of("wrong","data")),1L));
        var schema=Json.MAPPER.valueToTree(Map.of("type","object","properties",Map.of("name",Map.of("type","string")),"required",List.of("name")));
        assertThrows(Failure.class,() -> agent(engine,model,config("off",false),"attempt").extract("Read name",schema));
    }
    @Test void cacheClearLeavesPermanentJournalsUntouched() throws Exception {
        Files.createDirectories(root.resolve("journal"));Files.writeString(root.resolve("journal/record.json"),"retained");
        var cache=new CacheStore(root.resolve("cache"));cache.put("a".repeat(64),List.of(new CacheStore.Action("fill",SemanticTarget.label("Name","edit-dialog"),"name",null)));
        cache.clear();assertTrue(cache.list().isEmpty());assertEquals("retained",Files.readString(root.resolve("journal/record.json")));
    }
}
