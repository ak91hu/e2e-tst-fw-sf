package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SfE2eCliTest {
    @Test void emptyDefaultSelectionDoesNotAuthenticateAndMissingMappingInputsFailBeforeCredentials() {
        var cli=SfE2eCli.commandLine();var output=new StringWriter();var error=new StringWriter();
        cli.setOut(new PrintWriter(output));cli.setErr(new PrintWriter(error));
        assertEquals(0,cli.execute("list","--config","../sf-e2e.yaml","--json"));
        assertEquals(2,cli.execute("run","--config","../sf-e2e.yaml"));assertTrue(error.toString().contains("NO_TESTS"));
        assertEquals(2,cli.execute("map-ui","--config","../sf-e2e.yaml","--locator-mode","read-write","--page","sample"));
        assertTrue(error.toString().contains("Supply --page and --route"));
    }
    private static class Engine implements UiEngine {
        int observations,polls;
        public Observation observe(String scope,Deadline d) {
            observations++;
            if(observations<3) throw new Failure("AUTOMATION_UNSUPPORTED","Dialog is not attached yet");
            var node=new Observation.Node("node-"+observations,SemanticTarget.role("heading","Details",scope),"Details","",false,false);
            return new Observation(observations,Instant.now(),"https://org/lightning/page/home",List.of(node),false,"COMPLETE","SAFE",null);
        }
        public Observation.Node resolve(SemanticTarget t,Observation o) { return o.nodes().getFirst(); }
        public void poll(Deadline d) { polls++; }
        public void perform(Action a,Observation o,Deadline d) { throw new AssertionError("Readiness must not act"); }
        public void open(String route,Deadline d) {}
        public String route() { return ""; }
        public String capabilityVersion() { return "test"; }
        public Map<String,String> readVisibleFields(Deadline d) { return Map.of(); }
        public byte[] captureMaskedPng(Deadline d) { return new byte[0]; }
        public void close() {}
    }
    @Test void mappingWaitsForLateDialogAndStableDescriptionsWithoutTransientNodeIds() {
        var engine=new Engine();
        SfE2eCli.MapUi.awaitStableScope(engine,"dialog",Deadline.after(Duration.ofSeconds(5)));
        assertEquals(8,engine.observations);assertEquals(7,engine.polls);
    }
    @Test void readinessWaitsForDeclaredTargetWithoutUiActions() {
        var engine=new Engine();
        SfE2eCli.MapUi.awaitReady(engine,SemanticTarget.role("heading","Details","page"),Deadline.after(Duration.ofSeconds(5)));
        assertEquals(3,engine.observations);assertEquals(2,engine.polls);
    }
    @Test void arbitraryPersonaNamesAndNonLightningAppRoutesAreAccepted() {
        var cli=SfE2eCli.commandLine();var error=new StringWriter();cli.setErr(new PrintWriter(error));
        assertEquals(2,cli.execute("map-ui","--config","../sf-e2e.yaml","--persona","reviewer","--page","sample"));
        assertTrue(error.toString().contains("Supply --page and --route"));
        assertTrue(SfE2eCli.validRoute("/home/home.jsp"));assertTrue(SfE2eCli.validRoute("/s/widget"));
        assertThrows(Failure.class,() -> SfE2eCli.validRoute("//foreign.example/page"));
    }
}
