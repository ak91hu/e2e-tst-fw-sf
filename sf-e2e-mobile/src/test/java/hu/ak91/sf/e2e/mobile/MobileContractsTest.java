package hu.ak91.sf.e2e.mobile;

import hu.ak91.sf.e2e.core.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MobileContractsTest {
    @TempDir Path root;
    HttpServer server;
    AtomicInteger clicks=new AtomicInteger(),finds=new AtomicInteger(),deletes=new AtomicInteger();
    boolean duplicate,disabled,uncertain,wrongIdentity;
    String platform="Android",driver="UiAutomator2";
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e -> {
            String path=e.getRequestURI().getPath(),value="null";int status=200;
            if(path.equals("/session")) value=Json.string(Map.of("sessionId","native-1","capabilities",Map.of("platformName",platform,"automationName",driver,"platformVersion","35","appPackage","supplied.package")));
            else if(path.endsWith("/elements")) {finds.incrementAndGet();String body=new String(e.getRequestBody().readAllBytes());String element="{\"element-6066-11e4-a52e-4f735466cecf\":\"element-1\"}";value=body.contains("old-id")?"[]":"["+element+(duplicate?","+element:"")+"]";}
            else if(path.endsWith("/attribute/name")) value=wrongIdentity?"\"Other\"":"\"Save\"";
            else if(path.endsWith("/displayed")) value="true";
            else if(path.endsWith("/enabled")) value=disabled?"false":"true";
            else if(path.endsWith("/click")) {clicks.incrementAndGet();if(uncertain) {status=500;value="{\"error\":\"unknown error\",\"message\":\"PRIVATE_SENTINEL\"}";} }
            else if(path.endsWith("/text")) value="\"Save\"";
            else if(e.getRequestMethod().equals("DELETE")) deletes.incrementAndGet();
            byte[] bytes=("{\"value\":"+value+"}").getBytes();e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
    }
    @AfterEach void stop() {server.stop(0);}
    MobileSession session(List<Map<String,Object>> events) {return new MobileSession("http://127.0.0.1:"+server.getAddress().getPort(),Map.of("platformName","Android","appium:automationName","UiAutomator2","appium:appPackage","supplied.package"),Deadline.after(Duration.ofSeconds(10)),new SecretRegistry(),events::add);}
    MobileSession.MapDocument map(MobileSession s) {Path file=root.resolve("native-map.json");s.saveMap(file,Map.of("save",List.of(new MobileSession.Target("id","old-id","Save"),new MobileSession.Target("accessibility id","Save","Save"))));return s.loadMap(file);}
    @Test void nativeFlowUsesFreshIdentityAndReviewedFallbackWithoutAProvider() {
        var events=new ArrayList<Map<String,Object>>();try(var s=session(events)) {var map=map(s);s.click(map,"save");s.expectText(map,"save","Save");assertEquals(4,finds.get());assertEquals(1,clicks.get());assertThrows(Failure.class,() -> s.expectText(map,"save","Wrong"));}assertEquals(1,deletes.get());assertFalse(Json.string(events).contains("PRIVATE_SENTINEL"));
    }
    @Test void ambiguityPermissionAndWrongIdentityFailBeforeDispatch() {
        try(var s=session(new ArrayList<>())) {var map=map(s);duplicate=true;assertEquals("TARGET_AMBIGUOUS",assertThrows(Failure.class,() -> s.click(map,"save")).code());duplicate=false;disabled=true;assertEquals("TARGET_NOT_ACTIONABLE",assertThrows(Failure.class,() -> s.click(map,"save")).code());disabled=false;wrongIdentity=true;assertEquals("TARGET_IDENTITY_MISMATCH",assertThrows(Failure.class,() -> s.click(map,"save")).code());assertEquals(0,clicks.get());}
    }
    @Test void uncertainNativeActionIsNeverRepeated() {
        try(var s=session(new ArrayList<>())) {uncertain=true;assertEquals("ACTION_COMMIT_UNCERTAIN",assertThrows(Failure.class,() -> s.click(map(s),"save")).code());assertEquals(1,clicks.get());}
    }
    @Test void mapCorruptionAndWrongNamespaceAreRejected() {
        try(var s=session(new ArrayList<>())) {map(s);var file=root.resolve("native-map.json");var doc=Json.read(file,MobileSession.MapDocument.class);Json.write(file,new MobileSession.MapDocument(1,"browser-map",doc.targets(),doc.checksum()));assertThrows(Failure.class,() -> s.loadMap(file));}
    }
    @Test void reviewedFallbackIsPersistedAndReusedAfterRestart() {
        var events=new ArrayList<Map<String,Object>>();Path file=root.resolve("native-map.json");try(var s=session(events)) {map(s);s.click(file,"save");assertEquals("accessibility id",s.loadMap(file).targets().get("save").getFirst().using());assertEquals(2,finds.get());}
        try(var s=session(events)) {s.click(file,"save");assertEquals(3,finds.get());assertEquals(2,clicks.get());}assertEquals(1,events.stream().filter(e -> "native-locator-healed".equals(e.get("kind"))).count());
    }
    @Test void iosProtocolUsesItsOwnNamespaceAndRejectsAnUnconfirmedDriver() {
        platform="iOS";driver="XCUITest";try(var s=new MobileSession("http://127.0.0.1:"+server.getAddress().getPort(),Map.of("platformName","iOS","appium:automationName","XCUITest","appium:bundleId","supplied.bundle"),Deadline.after(Duration.ofSeconds(10)),new SecretRegistry(),e -> {})) {s.click(map(s),"save");assertEquals(1,clicks.get());}
        driver="WrongDriver";assertEquals("MOBILE_CAPABILITY_CHANGED",assertThrows(Failure.class,() -> new MobileSession("http://127.0.0.1:"+server.getAddress().getPort(),Map.of("platformName","iOS","appium:automationName","XCUITest"),Deadline.after(Duration.ofSeconds(10)),new SecretRegistry(),e -> {})).code());assertEquals(2,deletes.get());
    }
}
