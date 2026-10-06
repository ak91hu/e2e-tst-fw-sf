package hu.ak91.sf.e2e.engine;
import hu.ak91.sf.e2e.core.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GeneralUiHarnessIT {
    @TempDir Path root;
    @Test void namedScopeIsResolvedAcrossApprovedFramesAndGlobalDuplicatesAreRejected() throws Exception {
        var duplicate=new java.util.concurrent.atomic.AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String dialog="<section role=dialog aria-label='Egyedi ablak'><label for=x>Egyedi mező</label><input id=x></section>";
        server.createContext("/",exchange -> {
            String page=exchange.getRequestURI().getPath().equals("/frame")?dialog:
                "<button>Main control</button><iframe src='/frame'></iframe>"+(duplicate.get()?dialog:"");
            byte[] html=page.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.sendResponseHeaders(200,html.length);
            exchange.getResponseBody().write(html);exchange.close();
        });server.start();
        try {
            String origin="http://127.0.0.1:"+server.getAddress().getPort();var c=FrameworkConfig.defaults();
            var config=new FrameworkConfig(1,"frame-harness","checks",new FrameworkConfig.Org("isolated","auto",origin,List.of(),1,""),
                c.ui(),c.auth(),c.personas(),c.execution(),c.agent(),c.cache(),c.retention(),c.evidence(),c.report());
            var deadline=Deadline.after(Duration.ofSeconds(60));
            try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline)) {
                engine.open("/app",deadline);
                var observation=engine.observe("dialog:Egyedi ablak",deadline);assertTrue(observation.complete());
                assertEquals(origin+"/frame",engine.resolve(SemanticTarget.label("Egyedi mező","dialog:Egyedi ablak"),observation).attributes().get("frame"));
                engine.action("fill",SemanticTarget.label("Egyedi mező","dialog:Egyedi ablak"),"Frame value",deadline);
                assertEquals("Frame value",engine.resolve(SemanticTarget.label("Egyedi mező","dialog:Egyedi ablak"),engine.observe("dialog:Egyedi ablak",deadline)).value());
                duplicate.set(true);engine.open("/app",deadline);
                assertEquals("AUTOMATION_UNSUPPORTED",assertThrows(Failure.class,() -> engine.observe("dialog:Egyedi ablak",deadline)).code());
            }
        } finally { server.stop(0); }
    }
    @Test void authorizedSessionWorksOnClassicAndExperienceRoutesWithLocalizedUi() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            boolean authenticated=Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"),"").contains("sid=PRIVATE_SESSION_SENTINEL");
            var html=(authenticated?"<h1>Általános alkalmazás</h1><label for=x>Egyedi mező</label><input id=x><button>Mentés</button>":"<h1>Unauthorized</h1>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.sendResponseHeaders(200,html.length);exchange.getResponseBody().write(html);exchange.close();
        });server.start();
        try {
            String origin="http://127.0.0.1:"+server.getAddress().getPort();var c=FrameworkConfig.defaults();
            var config=new FrameworkConfig(1,"generic-harness","checks",new FrameworkConfig.Org("generic","auto",origin,List.of(),1,""),
                new FrameworkConfig.Ui("hu","hu-HU","HUF","Europe/Budapest",1000,800),
                new FrameworkConfig.Auth("storage-state",c.auth().clientIdRef(),c.auth().privateKeyRef(),c.auth().privateKeyFileRef(),"","","/s/custom"),
                Map.of("reviewer",new FrameworkConfig.PersonaConfig("TEST_USER","TEST_STATE")),c.execution(),c.agent(),c.cache(),c.retention(),c.evidence(),c.report());
            var file=root.resolve("external-state.json");
            Json.write(file,Map.of("cookies",List.of(Map.of("name","sid","value","PRIVATE_SESSION_SENTINEL","domain","127.0.0.1","path","/","expires",-1,"httpOnly",true,"secure",false,"sameSite","Lax")),"origins",List.of()));
            var secrets=new SecretRegistry();var state=BrowserState.load(file,config.origins(),secrets);byte[] before=Files.readAllBytes(file);
            var deadline=Deadline.after(Duration.ofSeconds(60));
            try(var engine=new PlaywrightEngine(config,secrets,deadline,Persona.valueOf("reviewer"),state)) {
                engine.bootstrap(origin+"/s/custom",deadline);
                for(String route:List.of("/s/custom","/home/home.jsp")) {
                    engine.open(route,deadline);var snapshot=engine.observe("page",deadline);
                    assertTrue(snapshot.complete());assertNotNull(engine.resolve(SemanticTarget.role("heading","Általános alkalmazás","page"),snapshot));
                    engine.action("fill",SemanticTarget.label("Egyedi mező","page"),"Tetszőleges érték",deadline);
                    assertTrue(engine.captureMaskedPng(deadline).length>100);
                }
                assertThrows(Failure.class,() -> engine.observe("dialog:Missing",deadline));
            }
            assertArrayEquals(before,Files.readAllBytes(file));
        } finally { server.stop(0); }
    }
    @Test void customPicklistUsesTheObservedApprovedFrameInsteadOfTheMainPage() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            String page=exchange.getRequestURI().getPath().equals("/frame")?
                "<button role=combobox aria-label='Status' aria-controls=choices onclick=\"setTimeout(()=>document.getElementById('choices').innerHTML='<button role=option onclick=&quot;document.body.dataset.selected=1&quot;>Ready</button>',100)\">Select</button><div id=choices role=listbox></div>":
                "<button role=option onclick='window.wrong=1'>Ready</button><iframe src='/frame'></iframe>";
            byte[] html=page.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.sendResponseHeaders(200,html.length);exchange.getResponseBody().write(html);exchange.close();
        });server.start();
        try {
            String origin="http://127.0.0.1:"+server.getAddress().getPort();var c=FrameworkConfig.defaults();
            var config=new FrameworkConfig(1,"picklist-frame","checks",new FrameworkConfig.Org("local","auto",origin,List.of(),1,""),c.ui(),c.auth(),c.personas(),c.execution(),c.agent(),c.cache(),c.retention(),c.evidence(),c.report());
            var deadline=Deadline.after(Duration.ofSeconds(60));
            try(var engine=new PlaywrightEngine(config,new SecretRegistry(),deadline)) {
                engine.open("/app",deadline);engine.action("select",SemanticTarget.role("combobox","Status","page"),"Ready",deadline);
                engine.harnessScript("() => {if(window.wrong || document.querySelector('iframe').contentDocument.body.dataset.selected!=='1')throw Error('Wrong frame selected');}");
            }
        } finally {server.stop(0);}
    }
}
