package hu.ak91.sf.e2e.engine;

import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class EngineHarnessIT {
    private PlaywrightEngine engine;
    private Deadline deadline;
    private SecretRegistry secrets;
    @BeforeEach void start() { deadline=Deadline.after(Duration.ofSeconds(120));secrets=new SecretRegistry();engine=new PlaywrightEngine(FrameworkConfig.defaults(),secrets,deadline); }
    @AfterEach void close() { if(engine!=null) engine.close(); }
    @Test void nativeFormActionsAndNumericRoundTripUseRealBrowser() {
        engine.setHarnessHtml("<div role='dialog'><label for='name'>Opportunity Name</label><input id='name' value='Before'><label for='amount'>Amount</label><input id='amount' value='12345.67'><button>Save</button><button>Cancel</button></div>");
        engine.action("fill",SemanticTarget.label("Opportunity Name","edit-dialog"),"After",deadline);
        engine.fillNumeric(SemanticTarget.label("Amount","edit-dialog"),"0",deadline);
        var snapshot=engine.observe("edit-dialog",deadline);
        assertEquals("After",engine.resolve(SemanticTarget.label("Opportunity Name","edit-dialog"),snapshot).value());
        assertEquals("0",engine.resolve(SemanticTarget.label("Amount","edit-dialog"),snapshot).value());
    }
    @Test void customPicklistWaitsForItsOwnedOptionAndIgnoresUnrelatedVisibleChoices() {
        engine.setHarnessHtml("<button role=combobox aria-label='Status' aria-controls='choices' onclick=\"window.opens=(window.opens||0)+1;setTimeout(()=>{document.getElementById('choices').innerHTML='<button role=option onclick=&quot;document.body.dataset.selected=1&quot;>Ready</button>'},180)\">Select</button>"+
            "<div id=choices role=listbox></div><div role=listbox><button role=option onclick='document.body.dataset.wrong=1'>Ready</button></div>");
        engine.action("select",SemanticTarget.role("combobox","Status","page"),"Ready",deadline);
        engine.harnessScript("() => {if(window.opens!==1 || document.body.dataset.selected!=='1' || document.body.dataset.wrong)throw Error('Wrong or repeated selection');}");
    }
    @Test void duplicateOwnedPicklistOptionsFailWithoutChoosingEitherOrReopening() {
        engine.setHarnessHtml("<button role=combobox aria-label='Status' aria-controls=choices onclick='window.opens=(window.opens||0)+1'>Select</button>"+
            "<div id=choices role=listbox><button role=option onclick='window.selected=1'>Ready</button><button role=option onclick='window.selected=2'>Ready</button></div>");
        assertEquals("TARGET_AMBIGUOUS",assertThrows(Failure.class,() -> engine.action("select",SemanticTarget.role("combobox","Status","page"),"Ready",deadline)).code());
        engine.harnessScript("() => {if(window.opens!==1 || window.selected)throw Error('Ambiguous option was chosen or reopened');}");
    }
    @Test void shadowLabelsResolveInsideTheirOwnTreeEvenWithDuplicateIds() {
        engine.setHarnessHtml("<div id='first'></div><div id='second'></div>");
        engine.harnessScript("() => { for(const [id,name] of [['first','First amount'],['second','Second amount']]) {const r=document.getElementById(id).attachShadow({mode:'open'});r.innerHTML='<span id=label>'+name+'</span><input aria-labelledby=label>';r.getElementById=()=>{throw Error('Synthetic Salesforce restriction')};} }");
        var snapshot=engine.observe("page",deadline);
        assertNotEquals(engine.resolve(SemanticTarget.label("First amount","page"),snapshot).id(),engine.resolve(SemanticTarget.label("Second amount","page"),snapshot).id());
        engine.action("fill",SemanticTarget.label("Second amount","page"),"123",deadline);
        snapshot=engine.observe("page",deadline);
        assertEquals("",engine.resolve(SemanticTarget.label("First amount","page"),snapshot).value());
        assertEquals("123",engine.resolve(SemanticTarget.label("Second amount","page"),snapshot).value());
    }
    @Test void dynamicallyClosedShadowRootStaysClosedAndSupportsTrustedActions() {
        engine.setHarnessHtml("<div id='host'></div>");
        engine.harnessScript("() => { const r=document.getElementById('host').attachShadow({mode:'closed'});r.innerHTML='<label for=x>Closed field</label><input id=x>';if(document.getElementById('host').shadowRoot!==null)throw Error('Root was opened'); }");
        engine.action("fill",SemanticTarget.label("Closed field","page"),"closed value",deadline);
        var observation=engine.observe("page",deadline);
        assertEquals("closed value",engine.resolve(SemanticTarget.label("Closed field","page"),observation).value());
    }
    @Test void declarativeClosedShadowRootIsExplicitlyIncompleteAndWithheld() {
        engine.setHarnessHtml("<div><template shadowrootmode='closed'><input value='private'></template></div><p>Public</p>");
        assertFalse(engine.observe("page",deadline).complete());
        assertEquals("EVIDENCE_WITHHELD",assertThrows(Failure.class,() -> engine.captureMaskedPng(deadline)).code());
    }
    @Test void staleNodesCannotMutateReplacementInputs() {
        engine.setHarnessHtml("<label for=x>Name</label><input id=x>");
        var snapshot=engine.observe("page",deadline);var node=engine.resolve(SemanticTarget.label("Name","page"),snapshot);
        engine.harnessScript("() => document.getElementById('x').replaceWith(document.getElementById('x').cloneNode())");
        assertEquals("NODE_STALE",assertThrows(Failure.class,() -> engine.perform(new UiEngine.Action("fill",node.id(),"wrong"),snapshot,deadline)).code());
    }
    @Test void ambiguousTargetFailsWithoutClickingEitherSave() {
        engine.setHarnessHtml("<button>Save</button><button>Save</button>");
        var snapshot=engine.observe("page",deadline);
        assertEquals("TARGET_AMBIGUOUS",assertThrows(Failure.class,() -> engine.resolve(SemanticTarget.role("button","Save","page"),snapshot)).code());
    }
    @Test void detailsReaderIgnoresEditTextAndRejectsDuplicateLabels() {
        String row="<div class='slds-form-element test-id__output-root'><span class='slds-form-element__label'>Amount</span><div class='slds-form-element__control'>$0.00<button>Edit Amount</button></div></div>";
        engine.setHarnessHtml(row);
        assertEquals("$0.00",engine.readVisibleFields(deadline).get("Amount"));
        engine.setHarnessHtml(row+row);
        assertEquals("TARGET_AMBIGUOUS",assertThrows(Failure.class,() -> engine.readVisibleFields(deadline)).code());
    }
    @Test void secretsInNormalAndClosedShadowInputsAreRedactedAndMasked() throws Exception {
        String secret="SENTINEL_PRIVATE_123";secrets.register(secret);
        engine.setHarnessHtml("<label for=p>Password</label><input id=p type=password value='"+secret+"'><div id=host></div><p>"+secret+"</p>");
        engine.harnessScript("() => {const r=document.getElementById('host').attachShadow({mode:'closed'});r.innerHTML='<label for=x>Token</label><input id=x value=SENTINEL_PRIVATE_123>';}");
        assertFalse(Json.string(engine.observe("page",deadline)).contains(secret));
        byte[] png=engine.captureMaskedPng(deadline);var image=ImageIO.read(new ByteArrayInputStream(png));assertNotNull(image);
        int masked=0;for(int x=0;x<image.getWidth();x++) for(int y=0;y<image.getHeight();y++) if((image.getRGB(x,y)&0xffffff)==0xff00ff) masked++;
        assertTrue(masked>100,"Sensitive fields must have visible masking coverage");
    }
    @Test void unapprovedIframeWithholdsScreenshotAndMarksPartialObservation() {
        engine.setHarnessHtml("<p>Visible</p><iframe src='about:blank'></iframe>");
        assertFalse(engine.observe("page",deadline).complete());
        assertEquals("EVIDENCE_WITHHELD",assertThrows(Failure.class,() -> engine.captureMaskedPng(deadline)).code());
    }
    @Test void lightningObjectValuedComponentsDoNotBreakMaskingOrHideVisibleSecrets() throws Exception {
        secrets.register("PRIVATE_LWC_SENTINEL");
        engine.setHarnessHtml("<div id=component>PRIVATE_LWC_SENTINEL</div>");
        engine.harnessScript("() => {document.getElementById('component').value={selected:'internal'};}");
        var png=engine.captureMaskedPng(deadline);var image=ImageIO.read(new ByteArrayInputStream(png));assertNotNull(image);
        int masked=0;for(int x=0;x<image.getWidth();x++) for(int y=0;y<image.getHeight();y++) if((image.getRGB(x,y)&0xffffff)==0xff00ff) masked++;
        assertTrue(masked>100,"Visible text on object-valued Lightning components must still be masked");
    }
    @Test void ownerThreadAndOriginBoundariesAreEnforced() throws Exception {
        engine.setHarnessHtml("<button>Safe</button>");
        var error=CompletableFuture.supplyAsync(() -> assertThrows(Failure.class,engine::route)).get();
        assertEquals("ENGINE_THREAD_VIOLATION",error.code());
        assertEquals("POLICY_DENIED",assertThrows(Failure.class,() -> engine.open("https://example.com/",deadline)).code());
    }
    @Test void traversalLimitIsExplicitAndExpiredDeadlineCannotFill() {
        engine.setHarnessHtml("<label for=x>Name</label><input id=x>");
        assertThrows(Failure.class,() -> engine.action("fill",SemanticTarget.label("Name","page"),"late",Deadline.after(Duration.ofNanos(1))));
        engine.harnessScript("() => {const f=document.createDocumentFragment();for(let i=0;i<20001;i++)f.append(document.createElement('span'));document.body.append(f)}");
        assertEquals("AUTOMATION_UNSUPPORTED",assertThrows(Failure.class,() -> engine.observe("page",deadline)).code());
    }
    @Test void largeSalesforceListsRemainCompleteAndRecordIdentifiersAreNotFingerprintAnchors() {
        engine.setHarnessHtml("<table><tr role=row><td><a href='/lightning/r/Account/001000000000001AAA/view'>Record</a><input type=checkbox aria-label='Select Record'></td></tr></table><div id=rows></div>");
        engine.harnessScript("() => {document.getElementById('rows').innerHTML='<p>Visible text</p>'.repeat(1700);}");
        var observation=engine.observe("page",deadline);assertTrue(observation.complete());assertTrue(observation.nodes().size()>1500);
        var record=engine.resolve(SemanticTarget.role("link","Record","page"),observation);
        assertEquals("true",record.attributes().get("recordLink"));assertFalse(Json.string(record.attributes()).contains("001000000000001AAA"));
        assertEquals("true",engine.resolve(SemanticTarget.role("checkbox","Select Record","page"),observation).attributes().get("recordRow"));
    }
    @Test void localizedDialogsAndNamedScopesDoNotDependOnSaveOrEnglishLabels() {
        engine.setHarnessHtml("<div role=dialog aria-label='Új egyedi objektum'><label for=x>Megnevezés</label><input id=x><button>Mentés</button></div><div role=dialog aria-label='Súgó'><p>Tájékoztató</p><button>Bezárás</button></div>");
        engine.action("fill",SemanticTarget.label("Megnevezés","edit-dialog"),"Árvíztűrő",deadline);
        var observation=engine.observe("dialog:Új egyedi objektum",deadline);
        assertEquals("Árvíztűrő",engine.resolve(SemanticTarget.label("Megnevezés","dialog:Új egyedi objektum"),observation).value());
        assertEquals(1,engine.observe("dialog:Súgó",deadline).nodes().stream().filter(n -> "button".equals(n.target().role())).count());
        engine.setHarnessHtml("<section role=region aria-label='Ügyféladatok'><button>Frissítés</button></section>");
        assertNotNull(engine.resolve(SemanticTarget.role("button","Frissítés","region:Ügyféladatok"),engine.observe("region:Ügyféladatok",deadline)));
    }
    @Test void classicExperienceAndCustomLwcRecordLinksAreRecognizedWithoutStoringTheirIds() {
        engine.setHarnessHtml("<a href='/a0Z000000000001AAA'>Classic record</a><a href='/s/detail/a0Z000000000002AAA'>Portal record</a><a role=link data-record-id='a0Z000000000003AAA'>Custom record</a>");
        var snapshot=engine.observe("page",deadline);
        for(String name:List.of("Classic record","Portal record","Custom record")) {
            var node=engine.resolve(SemanticTarget.role("link",name,"page"),snapshot);
            assertEquals("true",node.attributes().get("recordLink"));assertFalse(Json.string(node.attributes()).contains("a0Z"));
        }
    }
}
