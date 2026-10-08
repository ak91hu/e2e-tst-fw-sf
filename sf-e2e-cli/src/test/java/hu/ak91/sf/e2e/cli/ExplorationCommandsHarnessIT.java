package hu.ak91.sf.e2e.cli;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.scenario.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.agent.*;
import hu.ak91.sf.e2e.engine.PlaywrightEngine;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
/** Production CLI orchestration with owner-thread synthetic browser/model sessions; live auth policy is unchanged. */
class ExplorationCommandsHarnessIT {
    @TempDir Path root;
    @Test void executionPublishesOnlyVerifiedRecipesAndRetainsFailedReproWithoutRetry() throws Exception {
        var app=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var mutations=new AtomicInteger();
        app.createContext("/app",e -> html(e,"<h1>Fixture</h1><label for=x>Input</label><input id=x><button onclick=\"fetch('/mutate');document.querySelector('output').textContent=document.querySelector('input').value\">Submit</button><output role=status aria-label=Result style='display:block;min-height:20px'></output>"));
        app.createContext("/mutate",e -> {mutations.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});
        var oracle=new CaseSpec.Step("expectText","Result",null,null,"expected",null);
        var spec=new CaseSpec("cli-flow","/app",List.of(new CaseSpec.Step("fill","Input",null,null,"input",null),new CaseSpec.Step("click","Submit",null,null,null,null),oracle));
        provider.createContext("/model",e -> {
            try {
                var raw=Json.MAPPER.readTree(e.getRequestBody().readAllBytes());var request=Json.MAPPER.readTree(raw.path("messages").get(1).path("content").asString());Object data;
                switch(request.path("kind").asString()) {
                    case "explore-cases" -> data=Map.of("cases",List.of(spec));
                    case "map" -> {var targets=new ArrayList<Map<String,String>>();int i=0;for(var n:request.path("observation").path("nodes"))targets.add(Map.of("key","control."+(++i),"nodeId",n.path("id").asString()));data=Map.of("targets",targets);}
                    case "case-target" -> {String target=request.path("params").path("target").asString();String id=null;for(var n:request.path("observation").path("nodes"))if(target.equals(n.path("target").path("name").asString())) {id=n.path("id").asString();break;}data=Map.of("nodeId",Objects.requireNonNull(id));}
                    default -> throw new IllegalStateException("Unexpected fixture request");
                }
                byte[] body=Json.string(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",Json.string(Map.of("tool","finish","verdict","PASSED","reason","Fixture response","evidenceRefs",List.of(),"data",data))))),"usage",Map.of("total_tokens",30))).getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);
            }catch(Exception failure) {e.sendResponseHeaders(500,-1);}finally {e.close();}
        });app.start();provider.start();
        try {
            Path config=root.resolve("project.yaml"),state=root.resolve("state.json"),charter=root.resolve("charter.json"),params=root.resolve("params.json");
            Json.write(state,Map.of("cookies",List.of(),"origins",List.of()));Json.write(charter,new ExplorationCharter(1,"campaign","Verify supplied result",List.of("/app"),Set.of("fill","click"),Set.of("Input","Submit"),List.of(oracle),2,10));
            Files.writeString(config,"schemaVersion: 1\nprojectId: cli-fixture\nprofile: mapping\norg:\n  baseUrl: https://application.example\nauth:\n  mode: storage-state\n  startRoute: /app\npersonas:\n  default:\n    usernameRef: E2E_UNUSED_USER_FIXTURE\n    storageStateRef: E2E_STATE_FIXTURE\nagent:\n  endpoint: http://127.0.0.1:"+provider.getAddress().getPort()+"/model\n  apiKeyRef: E2E_MODEL_FIXTURE\n  model: fixture\n  maxActions: 30\n  maxModelCalls: 30\nlocators:\n  dir: maps\n  mode: read-write\n  timeoutSeconds: 2\nreport:\n  root: reports\nretention:\n  journalDir: journals\nexecution:\n  testTimeoutSeconds: 60\n  assertionTimeoutSeconds: 2\n");
            for(boolean success:List.of(true,false)) {
                Json.write(params,Map.of("input","PRIVATE_FIXTURE_VALUE","expected",success?"PRIVATE_FIXTURE_VALUE":"INDEPENDENT_WRONG_EXPECTATION"));Path bundle=root.resolve(success?"passed":"failed");
                var cli=SfE2eCli.commandLine();ExplorationCommands command=cli.getSubcommands().get("explore-cases").getCommand();String origin="http://127.0.0.1:"+app.getAddress().getPort();
                command.sessions=(policy,persona,id,route,values,mode,output,work) -> {
                    var deadline=Deadline.after(Duration.ofSeconds(30));var secrets=new SecretRegistry();var local=local(policy,origin);String namespace=LocatorStore.namespace(policy,persona);var store=new LocatorStore(Path.of(policy.locators().dir()),namespace,"read-write");
                    try(var engine=new PlaywrightEngine(local,secrets,deadline)) {var recipe=work.execute(engine,store,FixtureModelClient.local(policy.agent(),secrets),namespace,deadline,secrets,e -> {});recipe.save(output);return 0;}
                };
                command.learner=learn -> {
                    var policy=learn.input.options.config("mapping");var deadline=Deadline.after(Duration.ofSeconds(30));var secrets=new SecretRegistry();String namespace=LocatorStore.namespace(policy,learn.input.persona);var store=new LocatorStore(Path.of(policy.locators().dir()),namespace,"read-write");
                    try(var engine=new PlaywrightEngine(local(policy,origin),secrets,deadline)) {new CaseLearner(engine,store,FixtureModelClient.local(policy.agent(),secrets),policy,secrets,e -> {}).learn(Json.read(learn.spec,CaseSpec.class),namespace,learn.input.parameters(),deadline,learn.charter).save(learn.output);return 0;}
                };
                assertEquals(success?0:1,cli.execute("explore-cases","--config",config.toString(),"--charter",charter.toString(),"--params",params.toString(),"--output-dir",bundle.toString(),"--locator-mode","read-write","--execute"));
                var finding=Json.MAPPER.readTree(Files.readString(bundle.resolve("findings.json")));assertEquals(success?"PASSED":"FAILED",finding.path("outcomes").get(0).path("status").asString());
                assertEquals(success,Files.exists(bundle.resolve("TST_cli-flow.recipe.local.json")));assertEquals(success,Files.exists(bundle.resolve("verified-suite.local.json")));assertTrue(Files.exists(bundle.resolve("TST_cli-flow.spec.local.json")));assertTrue(Files.exists(bundle.resolve("parameters.local.json.manifest.json")));assertTrue(Files.exists(bundle.resolve("REPRODUCTION.md")));
                if(!success)assertEquals("ASSERTION_FAILED",finding.path("outcomes").get(0).path("code").asString());
            }
            assertEquals(2,mutations.get(),"Each campaign submits exactly once, including the failed assertion campaign");
        } finally {app.stop(0);provider.stop(0);}
    }
    static FrameworkConfig local(FrameworkConfig policy,String origin) {var c=FrameworkConfig.defaults();return new FrameworkConfig(1,"cli-fixture","checks",new FrameworkConfig.Org("fixture","auto",origin,List.of(),1,""),c.ui(),c.auth(),c.personas(),policy.execution(),policy.agent(),c.cache(),c.retention(),c.evidence(),c.report(),policy.locators());}
    static void html(com.sun.net.httpserver.HttpExchange e,String content) throws java.io.IOException {byte[] body=content.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();}
}
