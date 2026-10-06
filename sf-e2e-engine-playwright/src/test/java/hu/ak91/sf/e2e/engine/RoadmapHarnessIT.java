package hu.ak91.sf.e2e.engine;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.quality.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadmapHarnessIT {
    @TempDir Path root;
    Deadline deadline() {return Deadline.after(Duration.ofSeconds(60));}
    FrameworkConfig config(String origin,String browser) {
        var c=FrameworkConfig.defaults();return new FrameworkConfig(1,"roadmap","checks",new FrameworkConfig.Org("generic","auto",origin,List.of(),1,""),new FrameworkConfig.Ui("en","en-US","USD","UTC",800,600,browser,browser.equals("chromium")?"cdp":"tracked",""),c.auth(),c.personas(),c.execution(),c.agent(),c.cache(),c.retention(),c.evidence(),c.report());
    }
    @ParameterizedTest @ValueSource(strings={"chromium","firefox","webkit"})
    void everyBrowserHasFreshResolutionClosedShadowAndMaskedEvidence(String browser) {
        var c=config("",browser);var d=deadline();var secret=new SecretRegistry();secret.register("PRIVATE_SENTINEL");
        try(var engine=new PlaywrightEngine(c,secret,d)) {
            engine.setHarnessHtml("<label for=x>Field</label><input id=x><button>Save</button><div id=host></div>");engine.harnessScript("document.querySelector('#host').attachShadow({mode:'closed'}).innerHTML='<button>Closed action</button>'");
            engine.action("fill",SemanticTarget.label("Field","page"),"value",d);assertEquals("value",engine.resolve(SemanticTarget.label("Field","page"),engine.observe("page",d)).value());engine.action("click",SemanticTarget.role("button","Closed action","page"),null,d);assertTrue(engine.captureMaskedPng(d).length>100);
            var old=engine.observe("page",d);engine.observe("page",d);assertEquals("NODE_STALE",assertThrows(Failure.class,() -> engine.perform(new UiEngine.Action("click",old.nodes().getFirst().id(),null),old,d)).code());
            engine.harnessScript("(() => {const previous=Element.prototype.attachShadow;Element.prototype.attachShadow=function(options){return previous.call(this,options)};})()");assertEquals("EVIDENCE_WITHHELD",assertThrows(Failure.class,() -> engine.captureMaskedPng(d)).code());
        }
    }
    @Test void pooledRuntimeNeverSharesLocalStorageOrDomAcrossContexts() {
        var c=config("","chromium");var d=deadline();try(var runtime=new PlaywrightEngine.WorkerRuntime(c)) {
            try(var first=new PlaywrightEngine(c,new SecretRegistry(),d)) {first.setHarnessHtml("<button>First</button>");first.harnessScript("window.privateCase='first'");}
            try(var second=new PlaywrightEngine(c,new SecretRegistry(),d)) {second.setHarnessHtml("<button>Second</button>");assertThrows(Failure.class,() -> second.resolve(SemanticTarget.role("button","First","page"),second.observe("page",d)));second.harnessScript("if(window.privateCase) throw Error('Context leaked')");}
        }
    }
    @Test void ownedLookupClosedPicklistMultiselectAndRichText() {
        var d=deadline();try(var engine=new PlaywrightEngine(config("","chromium"),new SecretRegistry(),d)) {
            engine.setHarnessHtml("<input aria-label='Lookup' role=combobox aria-controls=results oninput=\"setTimeout(()=>document.querySelector('#results').style.display='block',30)\"><div id=results style='display:none'><div role=option onclick=\"this.textContent='Chosen'\">Exact result</div></div><div role=option>Exact result</div><div role=textbox aria-label='Rich text' contenteditable=true>Initial</div><select multiple aria-label='Native choices'><option>A</option><option>B</option></select><div id=host></div>");
            engine.action("lookup",SemanticTarget.role("combobox","Lookup","page"),"{\"query\":\"Ex\",\"option\":\"Exact result\"}",d);
            engine.action("richText",SemanticTarget.label("Rich text","page"),"Reviewed text",d);assertEquals("Reviewed text",engine.resolve(SemanticTarget.label("Rich text","page"),engine.observe("page",d)).text());
            engine.action("multiSelect",SemanticTarget.label("Native choices","page"),"[\"A\",\"B\"]",d);
            engine.harnessScript("document.querySelector('#host').attachShadow({mode:'closed'}).innerHTML=`<button role=combobox aria-label='Closed choices' aria-controls=owned onclick=\"this.getRootNode().querySelector('#owned').style.display='block'\">Choices</button><div id=owned role=listbox aria-multiselectable=true style='display:none'><div role=option aria-selected=false onclick=\"this.setAttribute('aria-selected',this.getAttribute('aria-selected')==='true'?'false':'true')\">A</div><div role=option aria-selected=true onclick=\"this.setAttribute('aria-selected',this.getAttribute('aria-selected')==='true'?'false':'true')\">B</div></div>`");
            engine.action("multiSelect",SemanticTarget.label("Closed choices","page"),"[\"A\"]",d);engine.harnessScript("const options=window.__sfE2e.elements().filter(e=>e.getAttribute('role')==='option' && ['A','B'].includes(e.textContent));if(options.find(e=>e.textContent==='A').getAttribute('aria-selected')!=='true' || options.find(e=>e.textContent==='B').getAttribute('aria-selected')!=='false') throw Error('Wrong selection')");
        }
    }
    @Test void duplicateLookupAndBoundedVirtualRowsFailInsteadOfGuessing() {
        var d=deadline();try(var engine=new PlaywrightEngine(config("","chromium"),new SecretRegistry(),d)) {
            engine.setHarnessHtml("<input aria-label=Lookup role=combobox aria-controls=results><div id=results><div role=option>Exact</div><div role=option>Exact</div></div>");assertEquals("TARGET_AMBIGUOUS",assertThrows(Failure.class,() -> engine.action("lookup",SemanticTarget.label("Lookup","page"),"{\"query\":\"E\",\"option\":\"Exact\"}",d)).code());
            engine.setHarnessHtml("<div role=grid aria-label=Records style='height:100px;overflow:auto' onscroll=\"if(this.scrollTop>=100)document.querySelector('#row').setAttribute('aria-label','Wanted')\"><div style='height:1000px'><div id=row role=row aria-label=First>First</div></div></div>");
            engine.action("gridFind",SemanticTarget.role("grid","Records","page"),"{\"row\":\"Wanted\",\"maxScrolls\":\"4\"}",d);
            assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> engine.action("gridFind",SemanticTarget.role("grid","Records","page"),"{\"row\":\"Missing\",\"maxScrolls\":\"1\"}",d)).code());
        }
    }
    @Test void popupDialogAndOwnedFileTransfersAreVerifiedAfterOneDispatch() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e -> {
            String path=e.getRequestURI().getPath();byte[] bytes=(path.equals("/download")?"expected-content":path.equals("/popup")?"<h1>Popup ready</h1>":"<label for=file>Upload</label><input id=file type=file><a href='/download' download>Download</a><button onclick=\"window.open('/popup')\">Popup</button><button onclick=\"document.querySelector('#result').textContent=confirm('Exact message')?'Accepted':'Dismissed'\">Confirm</button><p id=result>Pending</p>").getBytes();
            e.getResponseHeaders().set("Content-Type",path.equals("/download")?"application/octet-stream":"text/html");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();try {
            var d=deadline();String origin="http://127.0.0.1:"+server.getAddress().getPort();try(var engine=new PlaywrightEngine(config(origin,"chromium"),new SecretRegistry(),d)) {
                Path input=root.resolve("inputs");Files.createDirectories(input);Files.writeString(input.resolve("sample.txt"),"expected-content");List<Map<String,Object>> events=new ArrayList<>();engine.configureFiles(input,root.resolve("attempt/private-downloads"),events::add);engine.open("/app",d);
                engine.action("upload",SemanticTarget.label("Upload","page"),"sample.txt",d);assertThrows(Failure.class,() -> engine.action("upload",SemanticTarget.label("Upload","page"),"../escape.txt",d));
                engine.action("download",SemanticTarget.role("link","Download","page"),Json.string(Map.of("sha256",Json.hash("expected-content".getBytes()),"text","expected-content")),d);assertEquals(2,events.stream().filter(e -> e.get("kind").equals("file-transfer")).count());
                assertEquals("FILE_VERIFICATION_FAILED",assertThrows(Failure.class,() -> engine.action("download",SemanticTarget.role("link","Download","page"),Json.string(Map.of("sha256",Json.hash("expected-content".getBytes()),"text","Wrong expected content")),d)).code());
                engine.action("clickDialog",SemanticTarget.role("button","Confirm","page"),Json.string(Map.of("type","confirm","message","Exact message","decision","accept","prompt","")),d);engine.harnessScript("if(document.querySelector('#result').textContent!=='Accepted') throw Error('Dialog dismissed incorrectly')");
                assertThrows(Failure.class,() -> engine.action("clickDialog",SemanticTarget.role("button","Confirm","page"),Json.string(Map.of("type","confirm","message","Wrong","decision","accept","prompt","")),d));
                engine.action("clickPopup",SemanticTarget.role("button","Popup","page"),"/popup",d);assertTrue(engine.route().endsWith("/popup"));engine.switchPage("main",d);assertTrue(engine.route().endsWith("/app"));
            }
        }finally {server.stop(0);}
    }
    @Test void accessibilityKnownDefectAndPrivateTraceHaveSeparateEvidencePolicies() throws Exception {
        var d=deadline();try(var engine=new PlaywrightEngine(config("","chromium"),new SecretRegistry(),d)) {
            engine.setHarnessHtml("<button aria-label='Accessible'>Button</button>");var good=engine.accessibilityAudit(List.of("button-name"),d);QualityModules.accessibility(good,Set.of("critical","serious"),List.of(),Clock.systemUTC());
            engine.setHarnessHtml("<button style='width:50px;height:30px'></button>");var defect=engine.accessibilityAudit(List.of("button-name"),d);assertThrows(Failure.class,() -> QualityModules.accessibility(defect,Set.of("critical","serious"),List.of(),Clock.systemUTC()));
            engine.startQualityMonitoring();engine.startPrivateTrace(root.resolve("attempt/private-trace"),"attempt");engine.setHarnessHtml("<button>PRIVATE_SENTINEL</button>");engine.finishPrivateTrace(true);try(var paths=Files.list(root.resolve("attempt/private-trace"))) {assertTrue(paths.anyMatch(p -> p.toString().endsWith(".zip.manifest.json")));}
            engine.startPrivateTrace(root.resolve("success/private-trace"),"success");engine.finishPrivateTrace(false);try(var paths=Files.list(root.resolve("success/private-trace"))) {assertFalse(paths.anyMatch(p -> p.toString().endsWith(".zip")));}
        }
    }
}
