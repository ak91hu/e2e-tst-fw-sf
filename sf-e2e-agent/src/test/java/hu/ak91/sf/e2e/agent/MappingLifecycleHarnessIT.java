package hu.ak91.sf.e2e.agent;

import com.sun.net.httpserver.HttpServer;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.engine.PlaywrightEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Chromium and HTTP model protocol, isolated from any Salesforce org or business scenario. */
class MappingLifecycleHarnessIT {
    @TempDir Path root;
    @Test void mapOnceThenExecuteHealAndRestartWithTheProviderOffline() throws Exception {
        var label=new AtomicReference<>("Project code");var calls=new AtomicInteger();
        var application=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        application.createContext("/app",exchange -> {
            var html=("<form aria-label='Details'><label for=x>"+label.get()+"</label><input id=x name='Flexible__c'>"+
                "<button type=button onclick=\"document.querySelector('output').textContent=document.querySelector('input').value\">Continue</button>"+
                "<output role=status aria-label='Result' style='display:block;min-height:20px'></output></form>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
            exchange.sendResponseHeaders(200,html.length);exchange.getResponseBody().write(html);exchange.close();
        });
        provider.createContext("/model",exchange -> {
            calls.incrementAndGet();
            try {
                var body=Json.MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                var request=Json.MAPPER.readTree(body.path("messages").path(1).path("content").asText());
                var targets=new ArrayList<Map<String,String>>();
                for(var node:request.path("observation").path("nodes")) {
                    String key=switch(node.path("target").path("role").asText()) {
                        case "textbox" -> "details.field";
                        case "button" -> "details.continue";
                        case "status" -> "details.result";
                        default -> throw new IllegalStateException("Unexpected projected control");
                    };
                    targets.add(Map.of("key",key,"nodeId",node.path("id").asText()));
                }
                var completion=Map.of("tool","finish","verdict","PASSED","reason","Current controls mapped",
                    "evidenceRefs",List.of(),"data",Map.of("targets",targets));
                byte[] response=Json.string(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",Json.string(completion)))),
                    "usage",Map.of("total_tokens",50))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            } catch(Exception e) { exchange.sendResponseHeaders(500,-1); }
            finally { exchange.close(); }
        });
        application.start();provider.start();
        try {
            String origin="http://127.0.0.1:"+application.getAddress().getPort();var defaults=FrameworkConfig.defaults();
            var config=new FrameworkConfig(1,"lifecycle-harness","checks",new FrameworkConfig.Org("isolated","auto",origin,List.of(),1,""),
                defaults.ui(),defaults.auth(),defaults.personas(),defaults.execution(),defaults.agent(),defaults.cache(),defaults.retention(),defaults.evidence(),defaults.report());
            var deadline=Deadline.after(Duration.ofSeconds(90));var secrets=new SecretRegistry();
            var repository=new LocatorStore(root,"isolated","read-write");
            var agent=new FrameworkConfig.Agent("http://127.0.0.1:"+provider.getAddress().getPort()+"/model","protocol-harness","UNUSED",10,2,1000,30);
            var model=new JsonModelClient(agent,secrets,HttpClient.newHttpClient(),"LOCAL_PROTOCOL_CREDENTIAL");
            try(var engine=new PlaywrightEngine(config,secrets,deadline)) {
                engine.open("/app?mode=edit",deadline);
                assertEquals(3,new UiMapper(engine,repository,model,agent,secrets,event -> {}).map("workspace","page",deadline).size());
            }
            assertEquals(1,calls.get());provider.stop(0);
            var events=new ArrayList<Map<String,Object>>();
            var policy=new FrameworkConfig.Locators(root.toString(),"read-write",true,false,false,2,100,5,.90,.10);
            // Every phase opens a fresh context and reloads disk state; the provider is offline.
            for(int phase=0;phase<3;phase++) {
                if(phase==1) label.set("Internal reference");
                var disk=new LocatorStore(root,"isolated","read-write");
                try(var engine=new PlaywrightEngine(config,secrets,deadline)) {
                    engine.open("/app?mode=edit",deadline);
                    var ui=new HealingLocators(engine,disk,policy,deadline,events::add,
                        (entry,observation,wait) -> {throw new AssertionError("Model access after mapping is forbidden");});
                    ui.fill("workspace","details.field","RUNTIME_VALUE_"+phase);
                    ui.click("workspace","details.continue");
                    ui.expectText("workspace","details.result","RUNTIME_VALUE_"+phase);
                }
                assertEquals(phase==0?1:2,disk.get("workspace","details.field").revision());
            }
            assertEquals(1,calls.get());assertEquals(1,events.stream().filter(e -> "locator-healed".equals(e.get("kind"))).count());
            assertEquals(2,events.stream().filter(e -> "locator-retry".equals(e.get("kind"))).count());
            assertEquals("Internal reference",repository.get("workspace","details.field").primary().label());
            String saved=Files.readString(repository.path());assertFalse(saved.contains("RUNTIME_VALUE"));assertFalse(saved.contains("nodeId"));
            assertFalse(saved.contains("LOCAL_PROTOCOL_CREDENTIAL"));
        } finally { application.stop(0);provider.stop(0); }
    }
}
