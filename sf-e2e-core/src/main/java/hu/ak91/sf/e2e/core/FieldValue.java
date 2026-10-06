package hu.ak91.sf.e2e.core;

public record FieldValue<T>(State state, T value) {
    public enum State { ABSENT, BLANK, PRESENT, UNREADABLE }
    public FieldValue { Failure.require((state == State.PRESENT) == (value != null), "VALUE_INVALID", "Field state and value disagree"); }
    public static <T> FieldValue<T> absent() { return new FieldValue<>(State.ABSENT, null); }
    public static <T> FieldValue<T> blank() { return new FieldValue<>(State.BLANK, null); }
    public static <T> FieldValue<T> present(T value) { return new FieldValue<>(State.PRESENT, value); }
}
