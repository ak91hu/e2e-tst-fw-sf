package hu.ak91.sf.e2e.core.scenario;

import hu.ak91.sf.e2e.core.*;
import java.util.*;

public final class UiReadiness {
    private UiReadiness() {}
    // Node IDs and capture timestamps change on every observation; business state must not.
    private record State(SemanticTarget target,String text,String value,boolean disabled,boolean checked,Map<String,String> attributes) {}
    /** Semantic changes only: ephemeral node IDs, capture time and observation revisions do not spend judge calls. */
    public static String stateDigest(Observation current) {
        return Json.hash(Map.of("route",current.route(),"nodes",current.nodes().stream()
            .map(n -> new State(n.target(),n.text(),n.value(),n.disabled(),n.checked(),n.attributes())).toList()));
    }
    public static Observation stable(UiEngine engine,String scope,Deadline deadline) {
        String previous=null;int count=0;
        while(true) {
            deadline.check();
            try {
                var current=engine.observe(scope,deadline);
                if(current.complete() && !current.nodes().isEmpty() && current.nodes().stream().noneMatch(n -> "true".equals(n.attributes().get("busy")))) {
                    String hash=stateDigest(current);
                    count=hash.equals(previous)?count+1:0;previous=hash;
                    if(count>=3) return current;
                } else { previous=null;count=0; }
            } catch(Failure e) {
                if(!Set.of("TARGET_NOT_FOUND","NODE_STALE","AUTOMATION_UNSUPPORTED").contains(e.code())) throw e;
                previous=null;count=0;
            }
            engine.poll(deadline);
        }
    }
}
