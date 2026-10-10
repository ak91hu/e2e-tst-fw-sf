package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.engine.PlaywrightEngine;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class BatchLearningHarnessIT {
    @TempDir Path root;
    static class Fixture implements AutoCloseable {
        final HttpServer server;int calls;long bytes;boolean drift,invalid;List<Integer> caps=new ArrayList<>(),wireSizes=new ArrayList<>();List<String> requestedTargets=new ArrayList<>();
        Fixture() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",e -> {
                String change=drift?" oninput=\"document.getElementById('beta-label').firstChild.textContent='Updated beta'\"":"";
                send(e,"text/html","<label>Alpha<input data-testid='alpha'"+change+"></label><label id='beta-label'>Beta<input data-testid='beta'></label><label>Gamma<input data-testid='gamma'></label>");
            });
            server.createContext("/model",e -> {
                byte[] wire=e.getRequestBody().readAllBytes();calls++;bytes+=wire.length;wireSizes.add(wire.length);var body=Json.MAPPER.readTree(wire);caps.add(body.path("max_completion_tokens").asInt());
                var request=Json.MAPPER.readTree(body.path("messages").get(1).path("content").asString());
                assertFalse(Json.string(request).contains("PRIVATE_INPUT_SENTINEL"));Object data;
                if(request.path("kind").asString().equals("case-target-batch")) {
                    List<Map<String,Object>> selected=new ArrayList<>();
                    for(var target:request.path("params").path("targets")) {
                        requestedTargets.add(target.path("instruction").asString());
                        selected.add(Map.of("id",target.path("id").asString(),"nodeId",invalid?"invented":node(request,target.path("instruction").asString())));
                    }
                    data=Map.of("targets",selected);
                } else {requestedTargets.add(request.path("params").path("target").asString());data=Map.of("nodeId",node(request,request.path("params").path("target").asString()));}
                var response=new ModelClient.Response("finish",null,null,null,"PASSED","Observed fixture targets",null,List.of(),Json.MAPPER.valueToTree(data),null);
                long input=(wire.length+3)/4;long output=32;
                send(e,"application/json",Json.string(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",Json.string(response)))),"usage",Map.of("total_tokens",input+output,"prompt_tokens",input,"completion_tokens",output))));
            });server.start();
        }
        String origin() {return "http://127.0.0.1:"+server.getAddress().getPort();}
        String node(tools.jackson.databind.JsonNode request,String name) {
            if(name.startsWith("The field labelled ")) name=name.substring("The field labelled ".length());
            for(var node:request.path("observation").path("nodes")) if(node.path("attributes").path("testId").asString().equals(name.toLowerCase(Locale.ROOT))) return node.path("id").asString();
            throw new AssertionError("Requested fixture target is absent");
        }
        static void send(com.sun.net.httpserver.HttpExchange e,String type,String text) throws java.io.IOException {
            byte[] data=text.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type",type);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();
        }
        public void close() {server.stop(0);}
    }
    CaseSpec spec() {return new CaseSpec("multi-target","/app",List.of(
        new CaseSpec.Step("fill","Alpha",null,null,"input",null),new CaseSpec.Step("fill","Beta",null,null,"input",null),
        new CaseSpec.Step("fill","Gamma",null,null,"input",null),new CaseSpec.Step("expectValue","Gamma",null,null,"expected",null)));}
    FrameworkConfig config(Fixture fixture,boolean optimized) {
        var d=FrameworkConfig.defaults();var w=d.agent().workflow();
        var workflow=new FrameworkConfig.AiWorkflow(true,w.maxInputBytes(),w.maxOutputTokens(),w.outputTokenParameter(),1,0,BigDecimal.ZERO,BigDecimal.ONE,BigDecimal.TEN,null,optimized,optimized,optimized,8);
        var agent=new FrameworkConfig.Agent(fixture.origin()+"/model","local-protocol",d.agent().apiKeyRef(),40,10,100000,30,workflow);
        return new FrameworkConfig(1,"cost-harness","checks",new FrameworkConfig.Org("local","auto",fixture.origin(),List.of(),1,""),d.ui(),d.auth(),d.personas(),new FrameworkConfig.Execution(1,0,120,10,1,false),agent,d.cache(),d.retention(),d.evidence(),d.report(),d.locators());
    }
    Deadline deadline() {return Deadline.after(Duration.ofSeconds(90));}
    @Test void successiveCasesAnalyzeOnlyNewTargetsAndRestartedLearningNeedsNoProvider() throws Exception {
        try(var fixture=new Fixture()) {
            var config=config(fixture,true);var store=new LocatorStore(root.resolve("shared"),"benchmark","read-write");var secrets=new SecretRegistry();
            var transport=new JsonModelClient(config.agent(),secrets,HttpClient.newHttpClient(),"LOCAL_ONLY");
            var first=new CaseSpec("first","/app",List.of(new CaseSpec.Step("fill","The field labelled Alpha",null,null,"input",null),new CaseSpec.Step("expectValue","The field labelled Alpha",null,null,"expected",null)));
            try(var engine=new PlaywrightEngine(config,secrets,deadline())) {
                new CaseLearner(engine,store,new AiWorkflowClient(config.agent(),() -> transport,() -> transport,e -> {}),config,secrets,e -> {}).learn(first,"benchmark",Map.of("input","PRIVATE_INPUT_SENTINEL","expected","PRIVATE_INPUT_SENTINEL"),deadline());
            }
            assertEquals(1,fixture.calls);assertEquals(1,store.entries().size());var original=Map.copyOf(store.entries());fixture.calls=0;fixture.requestedTargets.clear();
            var second=new CaseSpec("second","/app",List.of(new CaseSpec.Step("fill","Beta",null,null,"input",null),new CaseSpec.Step("fill","The field labelled Alpha",null,null,"input",null),new CaseSpec.Step("fill","Gamma",null,null,"input",null),new CaseSpec.Step("expectValue","Gamma",null,null,"expected",null)));
            try(var engine=new PlaywrightEngine(config,secrets,deadline())) {
                new CaseLearner(engine,new LocatorStore(root.resolve("shared"),"benchmark","read-write"),new AiWorkflowClient(config.agent(),() -> transport,() -> transport,e -> {}),config,secrets,e -> {}).learn(second,"benchmark",Map.of("input","PRIVATE_INPUT_SENTINEL","expected","PRIVATE_INPUT_SENTINEL"),deadline());
            }
            assertEquals(1,fixture.calls);assertEquals(List.of("Beta","Gamma"),fixture.requestedTargets,"Known Alpha must not be included in the paid batch");assertEquals(3,store.entries().size());
            original.forEach((key,entry) -> assertEquals(entry,store.entries().get(key)));byte[] saved=Files.readAllBytes(store.path());fixture.calls=0;fixture.requestedTargets.clear();CaseRecipe recipe;
            var unavailable=new ScriptedModelClient(request -> {throw new AssertionError("Existing controls must not contact a provider");});
            try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline())) {
                recipe=new CaseLearner(engine,new LocatorStore(root.resolve("shared"),"benchmark","read-write"),unavailable,config,new SecretRegistry(),e -> {}).learn(spec(),"benchmark",Map.of("input","new-input","expected","new-input"),deadline());
            }
            assertEquals(0,unavailable.calls());assertEquals(0,fixture.calls);assertArrayEquals(saved,Files.readAllBytes(store.path()));
            recipe.save(root.resolve("shared.recipe.json"));
            try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline())) {new CaseRunner(engine,store,config,deadline(),e -> {}).run(CaseRecipe.load(root.resolve("shared.recipe.json")),"benchmark",store,Map.of("input","replay-value","expected","replay-value"));}
            var execution=new FrameworkConfig.Execution(1,0,90,1,1,false);
            var shortConfig=new FrameworkConfig(1,config.projectId(),config.profile(),config.org(),config.ui(),config.auth(),config.personas(),execution,config.agent(),config.cache(),config.retention(),config.evidence(),config.report(),config.locators());
            var events=new ArrayList<Map<String,Object>>();
            try(var engine=new PlaywrightEngine(shortConfig,new SecretRegistry(),deadline())) {
                assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> new CaseLearner(engine,store,unavailable,shortConfig,new SecretRegistry(),events::add).learn(spec(),"benchmark",Map.of("input","actual","expected","wrong"),deadline())).code());
            }
            assertEquals(3,events.stream().filter(e -> "locator-action".equals(e.get("kind"))).count());assertEquals(0,unavailable.calls());assertEquals(0,fixture.calls);assertArrayEquals(saved,Files.readAllBytes(store.path()));
        }
    }
    @Test void threeTargetsNeedOneHttpCallWithSmallerWireAndFreshReplayStillRejectsWrongResult() throws Exception {
        try(var fixture=new Fixture()) {
            long baselineBytes=0;BigDecimal baselineCost=null;CaseRecipe recipe=null;LocatorStore store=null;
            for(boolean optimized:List.of(false,true)) {
                fixture.calls=0;fixture.bytes=0;fixture.caps.clear();var config=config(fixture,optimized);var secrets=new SecretRegistry();var events=new ArrayList<Map<String,Object>>();
                var transport=new JsonModelClient(config.agent(),secrets,HttpClient.newHttpClient(),"LOCAL_ONLY");
                var model=new AiWorkflowClient(config.agent(),() -> transport,() -> {throw new AssertionError();},e -> events.add(Map.copyOf(e)));
                store=new LocatorStore(root.resolve(optimized?"optimized":"baseline"),"benchmark","read-write");
                try(var engine=new PlaywrightEngine(config,secrets,deadline())) {recipe=new CaseLearner(engine,store,model,config,secrets,events::add).learn(spec(),"benchmark",Map.of("input","PRIVATE_INPUT_SENTINEL","expected","PRIVATE_INPUT_SENTINEL"),deadline());}
                assertEquals(optimized?1:3,fixture.calls);assertEquals(1,events.stream().filter(e -> "assertion-passed".equals(e.get("kind"))).count());
                if(!optimized) {baselineBytes=fixture.bytes;baselineCost=(BigDecimal)model.snapshot().get("estimatedCostUsd");}
                else {
                    assertTrue(fixture.bytes<baselineBytes/2);assertTrue(((BigDecimal)model.snapshot().get("estimatedCostUsd")).compareTo(baselineCost)<0);
                    assertEquals(2,events.stream().filter(e -> "ai-batch-target-reused".equals(e.get("kind"))).count());assertEquals(List.of(896),fixture.caps);
                    System.out.println(Json.string(Map.of("benchmark","local-http-synthetic-usage","baselineCalls",3,"optimizedCalls",1,"baselineBytes",baselineBytes,"optimizedBytes",fixture.bytes,"baselineEstimatedUsd",baselineCost,"optimizedEstimatedUsd",model.snapshot().get("estimatedCostUsd"))));
                }
            }
            Path saved=root.resolve("recipe.json");recipe.save(saved);var replay=CaseRecipe.load(saved);var config=config(fixture,true);int calls=fixture.calls;
            try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline())) {new CaseRunner(engine,store,config,deadline(),e -> {}).run(replay,"benchmark",store,Map.of("input","PRIVATE_INPUT_SENTINEL","expected","PRIVATE_INPUT_SENTINEL"));}
            var selectedStore=store;
            try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline())) {
                assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> new CaseRunner(engine,selectedStore,config,deadline(),e -> {}).run(replay,"benchmark",selectedStore,Map.of("input","PRIVATE_INPUT_SENTINEL","expected","WRONG"))).code());
            }
            assertEquals(calls,fixture.calls);
        }
    }
    @Test void changedUiInvalidatesBatchAndInventedIdsNeverDispatchAnAction() throws Exception {
        try(var fixture=new Fixture()) {
            fixture.drift=true;var config=config(fixture,true);var secrets=new SecretRegistry();var events=new ArrayList<Map<String,Object>>();
            var transport=new JsonModelClient(config.agent(),secrets,HttpClient.newHttpClient(),"LOCAL_ONLY");
            var model=new AiWorkflowClient(config.agent(),() -> transport,() -> transport,events::add);var store=new LocatorStore(root.resolve("drift"),"benchmark","read-write");
            try(var engine=new PlaywrightEngine(config,secrets,deadline())) {new CaseLearner(engine,store,model,config,secrets,events::add).learn(spec(),"benchmark",Map.of("input","value","expected","value"),deadline());}
            assertEquals(2,fixture.calls);assertEquals(1,events.stream().filter(e -> "ai-batch-invalidated".equals(e.get("kind"))).count());
            fixture.invalid=true;fixture.calls=0;events.clear();var failed=new AiWorkflowClient(config.agent(),() -> transport,() -> transport,events::add);var empty=new LocatorStore(root.resolve("invalid"),"benchmark","read-write");
            try(var engine=new PlaywrightEngine(config,secrets,deadline())) {
                assertEquals("AI_WORKFLOW_REJECTED",assertThrows(Failure.class,() -> new CaseLearner(engine,empty,failed,config,secrets,events::add).learn(spec(),"benchmark",Map.of("input","value","expected","value"),deadline())).code());
            }
            assertEquals(2,fixture.calls);assertTrue(empty.entries().isEmpty());assertTrue(events.stream().noneMatch(e -> "locator-action".equals(e.get("kind"))));
        }
    }
    @Test void oversizedOptionalBatchFallsBackToAffordableSingleTargetsWithoutStopping() throws Exception {
        try(var fixture=new Fixture()) {
            var base=config(fixture,true);var a=base.agent();var w=a.workflow();
            // Calibrate the actual compact single-target wire size, then allow exactly that complete evidence.
            int inputLimit=w.maxInputBytes();
            for(boolean batching:List.of(false,true)) {
                var policy=new FrameworkConfig.AiWorkflow(true,inputLimit,w.maxOutputTokens(),w.outputTokenParameter(),1,0,BigDecimal.ZERO,BigDecimal.ONE,BigDecimal.TEN,null,true,true,batching,8);
                var agent=new FrameworkConfig.Agent(a.endpoint(),a.model(),a.apiKeyRef(),a.maxActions(),a.maxModelCalls(),a.maxTokens(),a.actTimeoutSeconds(),policy);
                var config=new FrameworkConfig(1,base.projectId(),base.profile(),base.org(),base.ui(),base.auth(),base.personas(),base.execution(),agent,base.cache(),base.retention(),base.evidence(),base.report(),base.locators());
                fixture.calls=0;fixture.wireSizes.clear();var events=new ArrayList<Map<String,Object>>();var secrets=new SecretRegistry();
                var transport=new JsonModelClient(agent,secrets,HttpClient.newHttpClient(),"LOCAL_ONLY");var model=new AiWorkflowClient(agent,() -> transport,() -> transport,events::add);
                var store=new LocatorStore(root.resolve("affordable-"+batching),"benchmark","read-write");
                try(var engine=new PlaywrightEngine(config,secrets,deadline())) {new CaseLearner(engine,store,model,config,secrets,events::add).learn(spec(),"benchmark",Map.of("input","value","expected","value"),deadline());}
                if(!batching) inputLimit=fixture.wireSizes.stream().mapToInt(Integer::intValue).max().orElseThrow();
                else {
                    assertEquals(3,fixture.calls);assertTrue(events.stream().anyMatch(e -> "ai-batch-deferred".equals(e.get("kind"))));
                    assertEquals(false,model.snapshot().get("stopped"));assertEquals(1,events.stream().filter(e -> "assertion-passed".equals(e.get("kind"))).count());
                }
            }
        }
    }
}
