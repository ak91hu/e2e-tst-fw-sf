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
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QualityCaseWorkflowHarnessIT {
    @TempDir Path root;
    @Test void learnsDisabledCountAndHiddenTargetsThenReplaysInFreshBrowserWithoutTheProvider() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange -> {
            byte[] html=("<button disabled data-testid='save'>Save</button><button data-testid='show' onclick=\"document.querySelector('#errors').innerHTML='<div role=alert aria-label=Problem data-testid=problem>Failure one</div><div role=alert aria-label=Problem data-testid=problem>Failure two</div>'\">Show</button><button data-testid='clear' onclick=\"document.querySelector('#errors').innerHTML=''\">Clear</button><section id=errors></section>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.sendResponseHeaders(200,html.length);exchange.getResponseBody().write(html);exchange.close();
        });server.start();
        try {
            var c=FrameworkConfig.defaults();String origin="http://127.0.0.1:"+server.getAddress().getPort();var config=new FrameworkConfig(1,"quality-workflow","checks",new FrameworkConfig.Org("local","auto",origin,List.of(),1,""),c.ui(),c.auth(),c.personas(),c.execution(),c.agent(),c.cache(),c.retention(),c.evidence(),c.report(),c.locators());
            var disk=new LocatorStore(root,"namespace","read-write");var deadline=Deadline.after(Duration.ofSeconds(60));var params=Map.of("count","2");
            var spec=new CaseSpec("negative-flow","/app",List.of(new CaseSpec.Step("expectDisabled","Save",null,null,null,null),new CaseSpec.Step("click","Show",null,null,null,null),new CaseSpec.Step("expectCount","Problem",null,null,"count",null),new CaseSpec.Step("click","Clear",null,null,null,null),new CaseSpec.Step("expectHidden","Problem",null,null,null,null)));
            var model=new ScriptedModelClient(request -> {
                String name=request.params().get("target").toString();String node=request.observation().nodes().stream().filter(n -> name.equals(n.target().name())).findFirst().orElseThrow().id();
                return new ModelClient.Response("finish",null,null,null,"PASSED","Observed",null,List.of(),Json.MAPPER.valueToTree(Map.of("nodeId",node)),10L);
            });
            CaseRecipe recipe;
            try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline)) { recipe=new CaseLearner(engine,disk,model,config,new SecretRegistry(),e -> {}).learn(spec,"namespace",params,deadline); }
            assertEquals(4,model.calls());recipe.save(root.resolve("recipe.json"));int calls=model.calls();
            try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline)) {new CaseRunner(engine,disk,config,deadline,e -> {}).run(CaseRecipe.load(root.resolve("recipe.json")),"namespace",disk,params);}
            assertEquals(calls,model.calls());assertEquals("expectHidden",recipe.steps().getLast().kind());assertEquals(recipe.steps().get(2).key(),recipe.steps().getLast().key());
        } finally {server.stop(0);}
    }
}
