package hu.ak91.sf.e2e.engine;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.quality.BrowserQuality;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real-browser fault injection: a visible success heading must not hide application defects. */
class QualityHarnessIT {
    @TempDir Path root;
    HttpServer server;String origin;FrameworkConfig config;Deadline deadline;SecretRegistry secrets;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            String path=exchange.getRequestURI().getPath();
            if(path.equals("/broken-transport")) { exchange.close();return; }
            int status=path.equals("/api-error")?503:200;
            String script=switch(path) {
                case "/crash" -> "setTimeout(()=>{throw Error('PRIVATE_DIAGNOSTIC_SENTINEL https://example.test/private?token=SECRET_QUERY')},10);console.error('Known console defect');fetch('/api-error?token=SECRET_QUERY');";
                case "/transport" -> "fetch('/broken-transport').catch(()=>{});";
                case "/overflow" -> "for(let i=0;i<8;i++)console.error('Issue '+i);";
                default -> "";
            };
            byte[] html=("<h1>Ready</h1><button data-testid='submit' disabled>Submit</button><script>"+script+"</script>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.sendResponseHeaders(status,html.length);exchange.getResponseBody().write(html);exchange.close();
        });server.start();origin="http://127.0.0.1:"+server.getAddress().getPort();var c=FrameworkConfig.defaults();
        config=new FrameworkConfig(1,"quality-harness","checks",new FrameworkConfig.Org("local","auto",origin,List.of(),1,""),c.ui(),c.auth(),c.personas(),
            new FrameworkConfig.Execution(1,0,60,5,1,false),c.agent(),c.cache(),c.retention(),c.evidence(),c.report(),c.locators());
        deadline=Deadline.after(Duration.ofSeconds(60));secrets=new SecretRegistry();secrets.register("PRIVATE_DIAGNOSTIC_SENTINEL");
    }
    @AfterEach void stop() { server.stop(0); }
    private void await(PlaywrightEngine engine,String kind) {
        var wait=deadline.child(Duration.ofSeconds(5));while(engine.browserIssues().stream().noneMatch(i -> i.kind().equals(kind))) engine.poll(wait);
    }
    @Test void pageConsoleAndHttpDefectsFailAlthoughTheUiLooksSuccessfulAndDiagnosticsAreRedacted() {
        try(var engine=new PlaywrightEngine(config,secrets,deadline)) {
            engine.startQualityMonitoring();engine.open("/crash",deadline);await(engine,"PAGE_ERROR");await(engine,"HTTP_SERVER_ERROR");
            assertNotNull(engine.resolve(SemanticTarget.role("heading","Ready","page"),engine.observe("page",deadline)));
            var issues=engine.browserIssues();assertTrue(issues.stream().anyMatch(i -> i.kind().equals("CONSOLE_ERROR")));
            assertEquals("APPLICATION_DEFECT",assertThrows(Failure.class,() -> BrowserQuality.verify(config.quality(),issues,List.of(),e -> {})).code());
            assertFalse(Json.string(issues).contains("PRIVATE_DIAGNOSTIC_SENTINEL"));assertFalse(Json.string(issues).contains("SECRET_QUERY"));assertTrue(issues.stream().allMatch(i -> !i.path().contains("?")));
        }
    }
    @Test void failedTransportIsCapturedAndTheQualityWindowStartsAfterBootstrapTraffic() {
        try(var engine=new PlaywrightEngine(config,secrets,deadline)) {
            engine.open("/crash",deadline);engine.poll(deadline);assertTrue(engine.browserIssues().isEmpty());
            engine.startQualityMonitoring();engine.open("/transport",deadline);await(engine,"REQUEST_FAILED");
            assertThrows(Failure.class,() -> BrowserQuality.verify(config.quality(),engine.browserIssues(),List.of(),e -> {}));
        }
    }
    @Test void boundedDiagnosticOverflowCannotSilentlyProduceAGreenResult() {
        var c=config;var limited=new FrameworkConfig(c.schemaVersion(),c.projectId(),c.profile(),c.org(),c.ui(),c.auth(),c.personas(),c.execution(),c.agent(),c.cache(),c.retention(),c.evidence(),c.report(),c.locators(),new FrameworkConfig.Quality(true,true,true,true,2,-1,-1,List.of()));
        try(var engine=new PlaywrightEngine(limited,secrets,deadline)) {
            engine.startQualityMonitoring();engine.open("/overflow",deadline);await(engine,"DIAGNOSTICS_OVERFLOW");assertEquals(3,engine.browserIssues().size());
            assertThrows(Failure.class,() -> BrowserQuality.verify(limited.quality(),engine.browserIssues(),List.of(),e -> {}));
        }
    }
    @Test void realDisabledStateCountAndDisappearanceAssertionsCatchTheOppositeState() {
        try(var engine=new PlaywrightEngine(config,secrets,deadline)) {
            engine.open("/healthy",deadline);var observation=engine.observe("page",deadline);var node=engine.resolve(SemanticTarget.role("button","Submit","page"),observation);
            var store=new LocatorStore(root,"namespace","read-write");store.putAll(List.of(LocatorEntry.mapped("p","submit",observation,node,engine.capabilityVersion())));
            var runner=new CaseRunner(engine,store,config,deadline,e -> {});runner.execute(new CaseRecipe.Step("expectDisabled","p","submit",null,null,null),Map.of());
            assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> runner.execute(new CaseRecipe.Step("expectEnabled","p","submit",null,null,null),Map.of())).code());
            engine.harnessScript("() => document.querySelector('button').style.display='none'");runner.execute(new CaseRecipe.Step("expectHidden","p","submit",null,null,null),Map.of());
            engine.harnessScript("() => {let b=document.querySelector('button');b.style.display='';b.textContent='Renamed';}");
            assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> runner.execute(new CaseRecipe.Step("expectHidden","p","submit",null,null,null),Map.of())).code());
        }
    }
}
