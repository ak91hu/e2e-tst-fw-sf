package hu.ak91.sf.e2e.engine;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Framework verification against real Chromium, never Salesforce business cases. */
class HealingHarnessIT {
    @TempDir Path root;
    private PlaywrightEngine engine;
    private Deadline deadline;
    private LocatorStore store;
    private final List<Map<String,Object>> events=new ArrayList<>();
    @BeforeEach void start() {
        deadline=Deadline.after(Duration.ofSeconds(30));
        engine=new PlaywrightEngine(FrameworkConfig.defaults(),new SecretRegistry(),deadline);
        store=new LocatorStore(root,"local-harness","read-write");
        engine.setHarnessHtml("<form aria-label='Details'><label for='company'>Company</label><input id='company' name='Company__c'></form>");
        var observation=engine.observe("page",deadline);
        var node=engine.resolve(SemanticTarget.role("textbox","Company","page"),observation);
        store.putAll(List.of(LocatorEntry.mapped("details","company",observation,node,engine.capabilityVersion())));
    }
    @AfterEach void close() { if(engine!=null) engine.close(); }
    private HealingLocators ui(LocatorStore repository,boolean strict) {
        var policy=new FrameworkConfig.Locators(root.toString(),repository.writable()?"read-write":"read-only",true,strict,false,2,100,5,0.90,0.10);
        return new HealingLocators(engine,repository,policy,deadline,events::add,(entry,observation,wait) -> { throw new AssertionError("AI must never be called"); });
    }
    private void rename() { engine.harnessScript("() => document.querySelector('label').textContent='Legal entity'"); }
    @Test void renamedLabelHealsAndPersistsThenReplaysWithoutAnyModelCall() {
        rename();ui(store,false).fill("details","company","Acme");
        var persisted=new LocatorStore(root,"local-harness","read-write").get("details","company");
        assertEquals("Legal entity",persisted.primary().label());assertEquals(2,persisted.revision());
        assertEquals("fingerprint",persisted.history().getFirst().reason());
        assertFalse(Json.string(persisted).contains("Acme"));
        ui(store,false).expectValue("details","company","Acme");
        assertEquals(1,events.stream().filter(e -> e.get("kind").equals("locator-healed")).count());
        assertEquals(2,store.get("details","company").revision());
    }
    @Test void changingSalesforceGeneratedDomIdsNeedsNoRepair() {
        engine.harnessScript("() => {document.querySelector('input').id='dynamic-847';document.querySelector('label').htmlFor='dynamic-847';}");
        ui(store,false).fill("details","company","Stable");
        assertEquals(1,store.get("details","company").revision());
    }
    @Test void readOnlyCiCanHealInMemoryWithoutChangingTheRepository() throws Exception {
        byte[] before=Files.readAllBytes(store.path());rename();
        var readOnly=new LocatorStore(root,"local-harness","read-only");
        ui(readOnly,false).fill("details","company","Read only");
        assertArrayEquals(before,Files.readAllBytes(store.path()));
        assertEquals(false,events.stream().filter(e -> e.get("kind").equals("locator-healed")).findFirst().orElseThrow().get("persisted"));
    }
    @Test void strictModeRejectsDriftWithoutModelOrMutation() {
        rename();assertEquals("LOCATOR_STALE",assertThrows(Failure.class,() -> ui(store,true).fill("details","company","Wrong")).code());
        assertEquals(1,store.get("details","company").revision());
        var observation=engine.observe("page",deadline);
        assertEquals("",engine.resolve(SemanticTarget.label("Legal entity","page"),observation).value());
    }
    @Test void duplicatedFingerprintFailsBeforeFillingEitherInput() {
        engine.setHarnessHtml("<form aria-label='Details'><label for=a>Legal entity A</label><input id=a name='Company__c'><label for=b>Legal entity B</label><input id=b name='Company__c'></form>");
        assertEquals("LOCATOR_LOW_CONFIDENCE",assertThrows(Failure.class,() -> ui(store,false).fill("details","company","Wrong")).code());
        assertEquals(1,store.get("details","company").revision());
        assertTrue(engine.observe("page",deadline).nodes().stream().filter(n -> "textbox".equals(n.target().role())).allMatch(n -> n.value().isEmpty()));
    }
}
