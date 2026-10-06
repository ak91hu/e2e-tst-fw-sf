package hu.ak91.sf.e2e.core;
public record SecretRef(String name, String purpose) {
    public SecretRef { Failure.require(name != null && name.matches("[A-Z][A-Z0-9_]+"), "CONFIG_INVALID", "Invalid secret reference"); }
}
