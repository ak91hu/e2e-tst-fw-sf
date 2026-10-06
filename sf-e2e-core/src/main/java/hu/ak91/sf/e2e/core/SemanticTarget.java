package hu.ak91.sf.e2e.core;
public record SemanticTarget(String role, String name, String label, String container) {
    public SemanticTarget {
        Failure.require((role != null && !role.isBlank()) || (name != null && !name.isBlank()) || (label != null && !label.isBlank()), "MODEL_OUTPUT_INVALID", "Target needs a role, name, or label");
    }
    public static SemanticTarget label(String label, String container) { return new SemanticTarget(null, null, label, container); }
    public static SemanticTarget role(String role, String name, String container) { return new SemanticTarget(role, name, null, container); }
}
