package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.engine.PlaywrightEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual browser outcome proof: fewer calls must not weaken business assertions. */
class AiWorkflowHarnessIT {
    @TempDir Path root;
    @Test void repositoryReuseIsMandatoryWithEitherResponseCachePolicyAndReplayStillRejectsWrongExpectations() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange -> {
            byte[] html="<label>Reference<input data-testid='reference'></label>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html");exchange.sendResponseHeaders(200,html.length);exchange.getResponseBody().write(html);exchange.close();
        });server.start();
        try {
            var c=FrameworkConfig.defaults();String origin="http://127.0.0.1:"+server.getAddress().getPort();
            var spec=new CaseSpec("supplied","/app",List.of(new CaseSpec.Step("fill","Reference",null,null,"input",null),new CaseSpec.Step("expectValue","Reference",null,null,"expected",null)));
            var params=Map.of("input","supplied-value","expected","supplied-value");var deadline=Deadline.after(Duration.ofSeconds(60));CaseRecipe optimized=null;LocatorStore optimizedStore=null;
            for(boolean reuse:List.of(false,true)) {
                var w=c.agent().workflow();var workflow=new FrameworkConfig.AiWorkflow(reuse,w.maxInputBytes(),w.maxOutputTokens(),w.outputTokenParameter(),1,0,java.math.BigDecimal.ZERO,null,null,null);
                var agent=new FrameworkConfig.Agent(c.agent().endpoint(),"local-evaluation",c.agent().apiKeyRef(),40,10,100000,30,workflow);
                var config=new FrameworkConfig(1,"ai-workflow-evaluation","checks",new FrameworkConfig.Org("local","auto",origin,List.of(),1,""),c.ui(),c.auth(),c.personas(),c.execution(),agent,c.cache(),c.retention(),c.evidence(),c.report(),c.locators());
                var provider=new ScriptedModelClient(request -> new ModelClient.Response("finish",null,null,null,"PASSED","Current field",null,List.of(),Json.MAPPER.valueToTree(Map.of("nodeId",request.observation().nodes().getFirst().id())),20L));
                var events=new ArrayList<Map<String,Object>>();var model=new AiWorkflowClient(agent,() -> provider,() -> {throw new AssertionError();},event -> events.add(Map.copyOf(event)));
                var store=new LocatorStore(root.resolve(reuse?"optimized":"baseline"),"namespace","read-write");CaseRecipe recipe;
                try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline)) {recipe=new CaseLearner(engine,store,model,config,new SecretRegistry(),events::add).learn(spec,"namespace",params,deadline);}
                assertEquals(1,provider.calls());assertEquals(1,events.stream().filter(e -> "ai-target-reused".equals(e.get("kind"))).count());
                assertEquals(recipe.steps().getFirst().key(),recipe.steps().getLast().key());assertEquals(1,store.entries().size());
                if(reuse) {optimized=recipe;optimizedStore=store;}
            }
            var config=new FrameworkConfig(1,"ai-workflow-evaluation","checks",new FrameworkConfig.Org("local","auto",origin,List.of(),1,""),c.ui(),c.auth(),c.personas(),c.execution(),c.agent(),c.cache(),c.retention(),c.evidence(),c.report(),c.locators());
            Path file=root.resolve("recipe.json");optimized.save(file);var recipe=CaseRecipe.load(file);var store=optimizedStore;
            try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline)) {new CaseRunner(engine,store,config,deadline,e -> {}).run(recipe,"namespace",store,params);}
            var shortExecution=new FrameworkConfig.Execution(1,0,60,5,1,false);
            var shortConfig=new FrameworkConfig(1,config.projectId(),"checks",config.org(),c.ui(),c.auth(),c.personas(),shortExecution,c.agent(),c.cache(),c.retention(),c.evidence(),c.report(),c.locators());
            try(var engine=new PlaywrightEngine(shortConfig,new SecretRegistry(),deadline)) {
                assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> new CaseRunner(engine,store,shortConfig,deadline,e -> {}).run(recipe,"namespace",store,Map.of("input","supplied-value","expected","wrong-result"))).code());
            }
        } finally {server.stop(0);}
    }
}
