package hu.ak91.sf.e2e.core;

import hu.ak91.sf.e2e.core.scenario.UiReadiness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UiReadinessTest {
    @ParameterizedTest @ValueSource(strings={"text","value","disabled","checked","route"})
    void waitsForBusinessStateToStopChanging(String field) {
        var engine=new SequenceEngine(field);
        var result=UiReadiness.stable(engine,"page",Deadline.after(Duration.ofSeconds(5)));
        assertEquals(8,engine.reads,"Five different states followed by three matching reads are required");
        assertEquals(8,result.revision());
    }

    @Test void ephemeralNodeIdsDoNotPreventStableReadiness() {
        var engine=new SequenceEngine("id");
        UiReadiness.stable(engine,"page",Deadline.after(Duration.ofSeconds(5)));
        assertEquals(4,engine.reads);
    }

    private static final class SequenceEngine implements UiEngine {
        private final String field;
        private int reads;
        SequenceEngine(String field) { this.field=field; }
        public Observation observe(String scope,Deadline deadline) {
            deadline.check();int state=Math.min(++reads,5);
            var node=new Observation.Node("ephemeral-"+reads,SemanticTarget.label("State","page"),
                field.equals("text")?"Text "+state:"Text",field.equals("value")?"Value "+state:"Value",
                field.equals("disabled") && state%2==0,field.equals("checked") && state%2==0,Map.of());
            return new Observation(reads,Instant.now(),field.equals("route")?"/page/"+state:"/page",List.of(node),false,"COMPLETE","SAFE",null);
        }
        public void poll(Deadline deadline) { deadline.check(); }
        public void open(String route,Deadline deadline) { throw new UnsupportedOperationException(); }
        public String route() { return "/page"; }
        public Observation.Node resolve(SemanticTarget target,Observation observation) { throw new UnsupportedOperationException(); }
        public void perform(Action action,Observation observation,Deadline deadline) { throw new UnsupportedOperationException(); }
        public Map<String,String> readVisibleFields(Deadline deadline) { throw new UnsupportedOperationException(); }
        public byte[] captureMaskedPng(Deadline deadline) { throw new UnsupportedOperationException(); }
        public String capabilityVersion() { return "readiness-fixture"; }
        public void close() {}
    }
}
