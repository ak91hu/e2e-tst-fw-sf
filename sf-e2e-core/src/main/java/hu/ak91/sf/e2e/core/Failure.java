package hu.ak91.sf.e2e.core;

public final class Failure extends RuntimeException {
    private final String code;
    public Failure(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
    public static void require(boolean condition, String code, String message) {
        if (!condition) throw new Failure(code, message);
    }
}
