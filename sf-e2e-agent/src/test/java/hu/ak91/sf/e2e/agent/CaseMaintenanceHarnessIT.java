package hu.ak91.sf.e2e.agent;

import com.sun.net.httpserver.HttpServer;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.engine.PlaywrightEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Local synthetic form contracts, including required fields and DOM layout drift; no org or paid provider. */
class CaseMaintenanceHarnessIT {
    @TempDir Path root;
    @Test void newRequiredFieldAndRenamedSharedControlProduceOneVerifiedWorkflowAndProviderFreeStrictRestart() throws Exception {
        try(var fixture=new Fixture()) {
            var store=new LocatorStore(root,"isolated","read-write");var provider=fixture.model();var params=Map.of("input","PRIVATE_INPUT","expected","PRIVATE_INPUT");
            CaseRecipe baseline;
            try(var engine=new PlaywrightEngine(fixture.config("mapping",false),new SecretRegistry(),deadline())) {
                baseline=new CaseLearner(engine,store,provider,fixture.config("mapping",false),new SecretRegistry(),e -> {}).learn(fixture.spec(),"isolated",params,deadline());
            }
            assertEquals(3,provider.calls());assertEquals(1,fixture.submissions.get());Path old=root.resolve("baseline.json");baseline.save(old);byte[] oldBytes=Files.readAllBytes(old);
            fixture.expanded.set(true);fixture.renamed.set(true);
            var patch=new CaseMaintenance.Changes(1,Json.hash(baseline),List.of(new CaseMaintenance.Insertion(2,List.of(
                new CaseSpec.Step("fill","Beta",null,null,"beta",null),new CaseSpec.Step("expectValue","Beta",null,null,"expectedBeta",null)))));
            var values=Map.of("input","MAINTAINED_INPUT","expected","MAINTAINED_INPUT","beta","PRIVATE_BETA","expectedBeta","PRIVATE_BETA");
            var maintenanceModel=fixture.model();var events=new ArrayList<Map<String,Object>>();CaseRecipe maintained;
            try(var engine=new PlaywrightEngine(fixture.config("mapping",false),new SecretRegistry(),deadline())) {
                maintained=new CaseLearner(engine,store,maintenanceModel,fixture.config("mapping",false),new SecretRegistry(),events::add)
                    .maintain(CaseMaintenance.plan(baseline,patch),"isolated",values,deadline());
            }
            assertEquals(1,maintenanceModel.calls());assertEquals(2,fixture.submissions.get());assertEquals(4,store.entries().size());
            assertEquals(baseline.steps(),List.of(maintained.steps().get(0),maintained.steps().get(3),maintained.steps().get(4)));
            assertEquals(1,events.stream().filter(e -> "locator-healed".equals(e.get("kind"))).count());
            assertArrayEquals(oldBytes,Files.readAllBytes(old));assertFalse(Json.string(maintained).contains("PRIVATE_"));
            Path updated=root.resolve("maintained.json");maintained.save(updated);byte[] map=Files.readAllBytes(store.path());
            var replayEvents=new ArrayList<Map<String,Object>>();
            try(var engine=new PlaywrightEngine(fixture.config("deterministic",true),new SecretRegistry(),deadline())) {
                var restart=new LocatorStore(root,"isolated","read-only");new CaseRunner(engine,restart,fixture.config("deterministic",true),deadline(),replayEvents::add)
                    .run(CaseRecipe.load(updated),"isolated",restart,values);
            }
            assertEquals(3,fixture.submissions.get());assertArrayEquals(map,Files.readAllBytes(store.path()));
            assertEquals(0,replayEvents.stream().filter(e -> "locator-healed".equals(e.get("kind"))).count());
            var unavailable=new ScriptedModelClient(request -> {throw new AssertionError("Repaired aliases must survive a fresh learner");});
            try(var engine=new PlaywrightEngine(fixture.config("mapping",false),new SecretRegistry(),deadline())) {
                var next=new CaseSpec("next","/form",List.of(new CaseSpec.Step("expectVisible","The field labelled Alpha",null,null,null,null)));
                var reused=new CaseLearner(engine,new LocatorStore(root,"isolated","read-write"),unavailable,fixture.config("mapping",false),new SecretRegistry(),e -> {})
                    .learn(next,"isolated",Map.of(),deadline());
                assertEquals(baseline.steps().getFirst().key(),reused.steps().getFirst().key());assertEquals(0,unavailable.calls());
            }
        }
    }
    @Test void changedDomXPathWithSameSemanticIdentityNeedsNoModelNoRepairAndNoRecipeRewrite() throws Exception {
        try(var fixture=new Fixture()) {
            var store=new LocatorStore(root,"isolated","read-write");var provider=fixture.model();var values=Map.of("input","VALUE","expected","VALUE");CaseRecipe baseline;
            try(var engine=new PlaywrightEngine(fixture.config("mapping",false),new SecretRegistry(),deadline())) {
                baseline=new CaseLearner(engine,store,provider,fixture.config("mapping",false),new SecretRegistry(),e -> {}).learn(fixture.spec(),"isolated",values,deadline());
            }
            byte[] map=Files.readAllBytes(store.path());fixture.wrapped.set(true);var events=new ArrayList<Map<String,Object>>();
            try(var engine=new PlaywrightEngine(fixture.config("deterministic",false),new SecretRegistry(),deadline())) {
                var maintained=new CaseLearner(engine,new LocatorStore(root,"isolated","read-write"),null,fixture.config("deterministic",false),new SecretRegistry(),events::add)
                    .maintain(CaseMaintenance.plan(baseline,null),"isolated",values,deadline());
                assertEquals(baseline,maintained);
            }
            assertArrayEquals(map,Files.readAllBytes(store.path()));assertEquals(0,events.stream().filter(e -> "locator-healed".equals(e.get("kind"))).count());
            assertEquals(3,provider.calls());assertEquals(2,fixture.submissions.get());
        }
    }
    private Deadline deadline() {return Deadline.after(Duration.ofSeconds(60));}
    private static class Fixture implements AutoCloseable {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicBoolean expanded=new AtomicBoolean(),renamed=new AtomicBoolean(),wrapped=new AtomicBoolean();final AtomicInteger submissions=new AtomicInteger();
        Fixture() throws Exception {
            server.createContext("/form",exchange -> {
                String alpha="<label for='alpha'>"+(renamed.get()?"Renamed Alpha":"Alpha")+"</label><input id='alpha' name='Alpha__c'>";
                if(wrapped.get() || expanded.get()) alpha="<div><div><div>"+alpha+"</div></div></div>";
                String beta=expanded.get()?"<label for='beta'>Beta</label><input id='beta' name='Beta__c' required>":"";
                byte[] body=("<form aria-label='Editor' onsubmit=\"event.preventDefault();fetch('/submit');document.querySelector('output').textContent=document.querySelector('#alpha').value\">"+
                    alpha+beta+"<button type='submit' data-testid='save'>Save</button><output role='status' aria-label='Result' style='display:block;min-height:20px'></output></form>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            server.createContext("/submit",exchange -> {submissions.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        }
        CaseSpec spec() {return new CaseSpec("maintenance","/form",List.of(new CaseSpec.Step("fill","The field labelled Alpha",null,null,"input",null),
            new CaseSpec.Step("click","Save",null,null,null,null),new CaseSpec.Step("expectText","Result",null,null,"expected",null)));}
        ScriptedModelClient model() {return new ScriptedModelClient(request -> {
            assertFalse(Json.string(request).contains("PRIVATE_"));
            String instruction=request.params().get("target").toString(),name=instruction.contains("Alpha")?"Alpha":instruction;
            var node=request.observation().nodes().stream().filter(n -> name.equals(n.target().name()) || name.equals(n.target().label())).findFirst().orElseThrow();
            return new ModelClient.Response("finish",null,null,null,"PASSED","Synthetic target",null,List.of(),Json.MAPPER.valueToTree(Map.of("nodeId",node.id())),10L);
        });}
        FrameworkConfig config(String profile,boolean strict) {
            var c=FrameworkConfig.defaults();return new FrameworkConfig(1,"maintenance-fixture",profile,
                new FrameworkConfig.Org("fixture","auto","http://127.0.0.1:"+server.getAddress().getPort(),List.of(),1,""),c.ui(),c.auth(),c.personas(),
                new FrameworkConfig.Execution(1,0,60,5,2,false),c.agent(),c.cache(),c.retention(),c.evidence(),c.report(),
                new FrameworkConfig.Locators("unused","read-write",true,strict,false,0,0,5,.90,.10));
        }
        public void close() {server.stop(0);}
    }
}
