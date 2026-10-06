package hu.ak91.sf.e2e.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
public record Observation(long revision, Instant capturedAt, String route, List<Node> nodes,
                          boolean truncated, String coverage, String redactionStatus, String degradationReason) {
    public record Node(String id, SemanticTarget target, String text, String value, boolean disabled, boolean checked,
                       Map<String,String> attributes) {
        public Node { attributes=attributes==null?Map.of():Map.copyOf(attributes); }
        public Node(String id,SemanticTarget target,String text,String value,boolean disabled,boolean checked) {
            this(id,target,text,value,disabled,checked,Map.of());
        }
    }
    public boolean complete() { return !truncated && "COMPLETE".equals(coverage) && "SAFE".equals(redactionStatus); }
}
