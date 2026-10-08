package hu.ak91.sf.e2e.agent;

import com.sun.net.httpserver.HttpServer;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.engine.PlaywrightEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Framework fixtures only: two pages, no Salesforce schema or consumer business cases. */
class CaseWorkflowHarnessIT {
    @TempDir Path root;
    @Test void textPlanLearnsCrossPageCaseThenReplaysAndHealsWithProviderStopped() throws Exception {
        try(var fixture=new Fixture()) {
            var config=fixture.config();var secrets=new SecretRegistry();var deadline=Deadline.after(Duration.ofSeconds(90));
            var store=new LocatorStore(root,"isolated","read-write");
            CaseRecipe recipe;
            try(var engine=new PlaywrightEngine(config,secrets,deadline)) {
                var learner=new CaseLearner(engine,store,fixture.model(secrets),config,secrets,e -> {});
                var spec=learner.plan("provided-case","/start","Follow Continue, enter ${input}, submit, expect result ${expected}.",Set.of("input","expected"),deadline);
                recipe=learner.learn(spec,"isolated",Map.of("input","PRIVATE_INPUT","expected","PRIVATE_INPUT"),deadline);
                recipe.save(root.resolve("recipe.json"));
            }
            assertEquals("TST_provided-case",recipe.id());assertEquals(2,recipe.pageObjects().size());
            assertNotEquals(recipe.steps().getFirst().page(),recipe.steps().get(1).page());
            assertEquals(recipe.steps().get(1).page(),recipe.steps().get(2).page());
            assertEquals(5,fixture.calls.get());fixture.provider.stop(0);fixture.label.set("Renamed field");
            var events=new ArrayList<Map<String,Object>>();
            for(int phase=0;phase<2;phase++) try(var engine=new PlaywrightEngine(config,secrets,deadline)) {
                var disk=new LocatorStore(root,"isolated","read-write");
                new CaseRunner(engine,disk,config,deadline,events::add).run(CaseRecipe.load(root.resolve("recipe.json")),"isolated",disk,Map.of("input","REPLAY_"+phase,"expected","REPLAY_"+phase));
            }
            assertTrue(events.stream().filter(e -> "case-replay-plan".equals(e.get("kind"))).allMatch(e -> "page-object-model".equals(e.get("executionModel"))));
            assertEquals(5,fixture.calls.get());assertEquals(1,events.stream().filter(e -> "locator-healed".equals(e.get("kind"))).count());
            assertEquals(2,events.stream().filter(e -> "locator-retry".equals(e.get("kind"))).count());
            assertEquals(2,store.get(recipe.steps().get(1).page(),recipe.steps().get(1).key()).revision());
            assertFalse(Files.readString(root.resolve("recipe.json")).contains("PRIVATE_INPUT"));
            assertFalse(Files.readString(store.path()).contains("REPLAY_"));assertFalse(fixture.requests.toString().contains("PRIVATE_INPUT"));
        }
    }
    @Test void autonomousDiscoveryNeverDispatchesButtonsAndGeneratedChecksReplayWithoutAi() throws Exception {
        try(var fixture=new Fixture()) {
            var config=fixture.config();var secrets=new SecretRegistry();var deadline=Deadline.after(Duration.ofSeconds(60));
            var disk=new LocatorStore(root,"isolated","read-write");CaseRecipe recipe;var events=new ArrayList<Map<String,Object>>();
            try(var engine=new PlaywrightEngine(config,secrets,deadline)) {
                recipe=new UiExplorer(engine,disk,fixture.model(secrets),config,secrets,events::add).discover("auto-map","/start","isolated",2,deadline);
            }
            assertEquals(4,recipe.steps().size());assertEquals(3,fixture.calls.get());assertEquals(0,fixture.submitted.get());
            var summary=events.stream().filter(e -> "discovery-completed".equals(e.get("kind"))).findFirst().orElseThrow();
            assertEquals("NO_MORE_ELIGIBLE_ROUTES",summary.get("stopReason"));assertEquals(0,summary.get("remainingRouteCount"));
            assertEquals(List.of("/start","/editor"),summary.get("visitedRoutes"));assertEquals(false,summary.get("applicationCoverageComplete"));
            fixture.provider.stop(0);
            try(var engine=new PlaywrightEngine(config,secrets,deadline)) { new CaseRunner(engine,disk,config,deadline,e -> {}).run(recipe,"isolated",disk,Map.of()); }
            assertEquals(0,fixture.submitted.get());assertEquals(3,fixture.calls.get());
        }
    }
    @Test void discoveryReportsLastPageFrontierEvenWhenNoNavigationDecisionWasAllowed() throws Exception {
        try(var fixture=new Fixture()) {
            var config=fixture.config();var secrets=new SecretRegistry();var deadline=Deadline.after(Duration.ofSeconds(60));
            var events=new ArrayList<Map<String,Object>>();var disk=new LocatorStore(root,"isolated","read-write");
            try(var engine=new PlaywrightEngine(config,secrets,deadline)) {
                new UiExplorer(engine,disk,fixture.model(secrets),config,secrets,events::add).discover("bounded","/start","isolated",1,deadline);
            }
            var summary=events.stream().filter(e -> "discovery-completed".equals(e.get("kind"))).findFirst().orElseThrow();
            assertEquals("PAGE_LIMIT",summary.get("stopReason"));assertEquals(List.of("/editor"),summary.get("remainingRoutes"));
            assertEquals(1,summary.get("remainingRouteCount"));assertEquals(0,summary.get("pendingPages"));
            assertEquals(1,fixture.calls.get());assertEquals(0,fixture.submitted.get());
            assertFalse(Json.string(summary).contains("sid="));assertFalse(Json.string(summary).contains("foreign.example"));
        }
    }
    @Test void modelStoppingNavigationDoesNotClaimTheKnownFrontierWasCovered() throws Exception {
        try(var fixture=new Fixture()) {
            fixture.selectRoutes.set(false);
            var config=fixture.config();var secrets=new SecretRegistry();var deadline=Deadline.after(Duration.ofSeconds(60));
            var events=new ArrayList<Map<String,Object>>();var disk=new LocatorStore(root,"isolated","read-write");
            try(var engine=new PlaywrightEngine(config,secrets,deadline)) {
                new UiExplorer(engine,disk,fixture.model(secrets),config,secrets,events::add).discover("stopped","/start","isolated",5,deadline);
            }
            var summary=events.stream().filter(e -> "discovery-completed".equals(e.get("kind"))).findFirst().orElseThrow();
            assertEquals("MODEL_SELECTION_STOPPED",summary.get("stopReason"));assertEquals(List.of("/editor"),summary.get("remainingRoutes"));
            assertEquals(List.of("/start"),summary.get("visitedRoutes"));assertEquals(false,summary.get("applicationCoverageComplete"));
            assertEquals(0,fixture.submitted.get());
        }
    }
    @Test void incorrectExpectedResultDoesNotProduceAPassingRecipeOrRetrySubmit() throws Exception {
        try(var fixture=new Fixture()) {
            var config=fixture.config();var secrets=new SecretRegistry();var deadline=Deadline.after(Duration.ofSeconds(60));
            var disk=new LocatorStore(root,"isolated","read-write");
            try(var engine=new PlaywrightEngine(config,secrets,deadline)) {
                var learner=new CaseLearner(engine,disk,fixture.model(secrets),config,secrets,e -> {});
                var failure=assertThrows(Failure.class,() -> learner.learn(Fixture.spec(),"isolated",Map.of("input","actual","expected","wrong"),deadline));
                assertEquals("ASSERTION_FAILED",failure.code());assertEquals(1,fixture.submitted.get());
            }
        }
    }
    @Test void navigationProbeExcludesSecretsRecordsButtonsAndForeignDestinations() throws Exception {
        try(var fixture=new Fixture()) {
            var config=fixture.config();var deadline=Deadline.after(Duration.ofSeconds(30));
            try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline)) {
                engine.open("/start",deadline);var snapshot=engine.observe("page",deadline);
                assertEquals(Set.of("/editor"),new HashSet<>(engine.navigationRoutes(snapshot,deadline).values()));
            }
        }
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer app,provider;final AtomicInteger calls=new AtomicInteger(),submitted=new AtomicInteger();
        final AtomicReference<String> label=new AtomicReference<>("Original field");final List<String> requests=new ArrayList<>();
        final AtomicBoolean selectRoutes=new AtomicBoolean(true);
        Fixture() throws Exception {
            app=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            app.createContext("/start",e -> html(e,"<h1>Start</h1><a href='/editor'>Continue</a><button onclick='fetch(\"/submit\")'>Do work</button>"+
                "<a href='/secur/frontdoor.jsp?sid=secret'>Authentication</a><a href='/lightning/r/Custom__c/a00000000000001AAA/view'>Record</a>"+
                "<a href='https://foreign.example/page'>Foreign</a><a href='/editor' onclick='fetch(\"/submit\")'>Script</a><a download href='/editor'>Download</a>"));
            app.createContext("/editor",e -> html(e,"<h1>Editor</h1><label for=f>"+label.get()+"</label><input id=f name='Flexible__c'>"+
                "<button onclick=\"fetch('/submit');document.querySelector('output').textContent=document.querySelector('input').value\">Submit</button>"+
                "<output role=status aria-label=Result style='display:block;min-height:20px'></output>"));
            app.createContext("/submit",e -> {submitted.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});
            provider.createContext("/model",e -> {
                calls.incrementAndGet();
                try {
                    var raw=Json.MAPPER.readTree(e.getRequestBody().readAllBytes());String content=raw.path("messages").get(1).path("content").asString();requests.add(content);
                    var request=Json.MAPPER.readTree(content);String kind=request.path("kind").asString();Object data;
                    switch(kind) {
                        case "case-plan" -> data=Map.of("steps",spec().steps());
                        case "case-target" -> {
                            String target=request.path("params").path("target").asString();String role=switch(target) {case "Continue" -> "link";case "Input" -> "textbox";case "Submit" -> "button";default -> "status";};
                            var nodes=request.path("observation").path("nodes");String id=null;
                            for(var n:nodes) if(n.path("target").path("role").asString().equals(role) && (!target.equals("Continue") || n.path("target").path("name").asString().equals("Continue"))) { id=n.path("id").asString();break; }
                            data=Map.of("nodeId",Objects.requireNonNull(id));
                        }
                        case "map" -> {
                            var targets=new ArrayList<Map<String,String>>();int index=0;
                            for(var n:request.path("observation").path("nodes")) targets.add(Map.of("key","control."+(++index),"nodeId",n.path("id").asString()));
                            data=Map.of("targets",targets);
                        }
                        case "discover-routes" -> data=Map.of("routes",selectRoutes.get()?List.of("/editor"):List.of());
                        default -> throw new IllegalStateException("Unexpected model kind");
                    }
                    var completion=Map.of("tool","finish","verdict","PASSED","reason","Current targets","evidenceRefs",List.of(),"data",data);
                    byte[] body=Json.string(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",Json.string(completion)))),"usage",Map.of("total_tokens",50))).getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);
                } catch(Exception failure) { e.sendResponseHeaders(500,-1); } finally { e.close(); }
            });app.start();provider.start();
        }
        static CaseSpec spec() { return new CaseSpec("provided-case","/start",List.of(
            new CaseSpec.Step("click","Continue",null,null,null,null),new CaseSpec.Step("fill","Input",null,null,"input",null),
            new CaseSpec.Step("click","Submit",null,null,null,null),new CaseSpec.Step("expectText","Result",null,null,"expected",null))); }
        FrameworkConfig config() {
            var d=FrameworkConfig.defaults();String origin="http://127.0.0.1:"+app.getAddress().getPort();
            var agent=new FrameworkConfig.Agent("http://127.0.0.1:"+provider.getAddress().getPort()+"/model","protocol-harness","UNUSED",30,30,10000,30);
            return new FrameworkConfig(1,"case-harness","checks",new FrameworkConfig.Org("isolated","auto",origin,List.of(),1,""),d.ui(),d.auth(),d.personas(),
                new FrameworkConfig.Execution(1,0,90,10,1,false),agent,d.cache(),d.retention(),d.evidence(),d.report(),
                new FrameworkConfig.Locators("unused","read-write",true,false,false,2,100,5,.90,.10));
        }
        ModelClient model(SecretRegistry secrets) { return new JsonModelClient(config().agent(),secrets,HttpClient.newHttpClient(),"LOCAL_PROTOCOL_CREDENTIAL"); }
        static void html(com.sun.net.httpserver.HttpExchange e,String text) throws java.io.IOException {
            byte[] html=text.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");e.sendResponseHeaders(200,html.length);e.getResponseBody().write(html);e.close();
        }
        public void close() {app.stop(0);provider.stop(0);}
    }
}
