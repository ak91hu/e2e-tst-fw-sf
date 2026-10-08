package hu.ak91.sf.e2e.engine;
import hu.ak91.sf.e2e.core.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
class SessionSecurityHarnessIT {
    @TempDir Path root;
    @Test void hostOnlySecretsWithholdEvidenceAndEncryptedBrowserSessionRestores() throws Exception {
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),getClass().getName(),root.toString());
        process.environment().put("E2E_OPAQUE_FIXTURE","OPAQUE_HOST_ONLY_SENTINEL");process.environment().put("E2E_VAULT_FIXTURE_KEY",Base64.getEncoder().encodeToString(new byte[32]));
        process.redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile());var child=process.start();
        boolean complete=child.waitFor(90,TimeUnit.SECONDS);if(!complete)child.destroyForcibly();assertTrue(complete,"Synthetic session-security child must terminate");assertEquals(0,child.exitValue(),Files.readString(root.resolve("child.log")));
    }
    public static void main(String[] args) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e -> {
            boolean restored=Objects.toString(e.getRequestHeaders().getFirst("Cookie"),"").contains("sid=SESSION_COOKIE_SENTINEL");
            byte[] body=("<h1>"+(restored?"Restored":"New")+"</h1><label for=x>Opaque field</label><input id=x>").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Set-Cookie","sid=SESSION_COOKIE_SENTINEL; HttpOnly; Path=/");e.getResponseHeaders().set("Content-Type","text/html");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
        });server.start();
        try {
            var c=FrameworkConfig.defaults();String origin="http://127.0.0.1:"+server.getAddress().getPort();
            var config=new FrameworkConfig(1,"session-fixture","checks",new FrameworkConfig.Org("fixture","auto",origin,List.of(),1,""),c.ui(),c.auth(),c.personas(),c.execution(),c.agent(),c.cache(),c.retention(),c.evidence(),c.report());
            var d=Deadline.after(Duration.ofSeconds(60));var secrets=new SecretRegistry();Path encrypted=Path.of(args[0],"session.local.json");
            try(var engine=new PlaywrightEngine(config,secrets,d)) {
                engine.open("/",d);assertTrue(engine.captureMaskedPng(d).length>0);engine.fillSecret(SemanticTarget.label("Opaque field","page"),new SecretRef("E2E_OPAQUE_FIXTURE","synthetic"),d);
                assertFalse(Json.string(engine.observe("page",d)).contains("OPAQUE_HOST_ONLY_SENTINEL"));assertEquals("EVIDENCE_WITHHELD",assertThrows(Failure.class,() -> engine.captureMaskedPng(d)).code());
                engine.startQualityMonitoring();assertThrows(Failure.class,() -> engine.startPrivateTrace(Path.of(args[0],"trace"),"fixture"));
                engine.harnessScript("() => localStorage.setItem('sessionToken','STORAGE_TOKEN_SENTINEL')");engine.exportEncryptedSession(encrypted,"E2E_VAULT_FIXTURE_KEY",Duration.ofHours(1),d);
                assertThrows(Failure.class,() -> engine.exportEncryptedSession(encrypted,"E2E_VAULT_FIXTURE_KEY",Duration.ofHours(1),d));
            }
            String encoded=Files.readString(encrypted);assertFalse(encoded.contains("SESSION_COOKIE_SENTINEL"));assertFalse(encoded.contains("STORAGE_TOKEN_SENTINEL"));
            String state=BrowserState.load(encrypted,List.of(origin),secrets);assertTrue(secrets.redact("STORAGE_TOKEN_SENTINEL").contains("[REDACTED]"));
            var auth=new FrameworkConfig.Auth("storage-state",c.auth().clientIdRef(),c.auth().privateKeyRef(),c.auth().privateKeyFileRef(),"","","/");
            var restore=new FrameworkConfig(1,"session-fixture","checks",config.org(),c.ui(),auth,c.personas(),c.execution(),c.agent(),c.cache(),c.retention(),c.evidence(),c.report());
            try(var engine=new PlaywrightEngine(restore,secrets,d,Persona.DEFAULT,state)) {engine.open("/",d);assertEquals("Restored",engine.resolve(SemanticTarget.role("heading","Restored","page"),engine.observe("page",d)).text());engine.harnessScript("() => {if(localStorage.getItem('sessionToken')!=='STORAGE_TOKEN_SENTINEL')throw Error('Session restoration failed');}");}
        } finally {server.stop(0);}
    }
}
