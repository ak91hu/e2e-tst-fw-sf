package hu.ak91.sf.e2e.agent;
import hu.ak91.sf.e2e.core.*;
public record StepParam(Object value, boolean unique) {
    public StepParam { Failure.require(value != null && !(value instanceof SecretRef),"POLICY_DENIED","Business agent parameters must be non-secret typed values"); }
    public static StepParam unique(Object value) { return new StepParam(value,true); }
    public static StepParam literal(Object value) { return new StepParam(value,false); }
}
