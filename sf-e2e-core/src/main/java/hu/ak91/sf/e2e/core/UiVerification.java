package hu.ak91.sf.e2e.core;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
/** Issued only after exact comparisons against a fresh persisted UI read. */
public record UiVerification(String id, String attemptId, String recordId, Map<String, Object> expected,
                             String route, String evidenceId, Instant capturedAt) {
    public UiVerification {
        Failure.require(expected!=null && !expected.isEmpty() && expected.keySet().stream().allMatch(k -> k!=null && !k.isBlank()) &&
            expected.values().stream().allMatch(java.util.Objects::nonNull),"UI_ASSERTION_FAILED","Verification requires explicit nonempty expected fields; use an empty string for a blank UI value");
        expected = Map.copyOf(expected);
    }
    public static UiVerification compare(String attempt, String record, Map<String, Object> expected,
                                         Map<String, Object> actual, String route, String evidence) {
        Failure.require(expected!=null && !expected.isEmpty() && actual!=null,"UI_ASSERTION_FAILED","Verification requires explicit expectations and an actual UI read");
        for (var entry : expected.entrySet()) {
            Object got = actual.get(entry.getKey()), want = entry.getValue();
            boolean equal = want instanceof java.math.BigDecimal a && got instanceof java.math.BigDecimal b
                ? a.compareTo(b) == 0 : java.util.Objects.equals(want, got);
            Failure.require(actual.containsKey(entry.getKey()) && equal, "VALUE_MISMATCH", "Persisted field mismatch: " + entry.getKey() + "; expected " + want + "; actual " + got);
        }
        return new UiVerification(UUID.randomUUID().toString(), attempt, record, expected, route, evidence, Instant.now());
    }
}
