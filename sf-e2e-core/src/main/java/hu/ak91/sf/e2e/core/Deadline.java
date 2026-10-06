package hu.ak91.sf.e2e.core;

import java.time.Duration;
public final class Deadline {
    private final long end;
    private Deadline(long end) { this.end = end; }
    public static Deadline after(Duration duration) {
        Failure.require(!duration.isNegative() && !duration.isZero(), "CONFIG_INVALID", "Deadline must be positive");
        return new Deadline(System.nanoTime() + duration.toNanos());
    }
    public Deadline child(Duration limit) { return new Deadline(Math.min(end, System.nanoTime() + limit.toNanos())); }
    public Duration remaining() {
        long nanos = end - System.nanoTime();
        Failure.require(nanos > 0, "STEP_TIMEOUT", "Execution deadline expired");
        return Duration.ofNanos(nanos);
    }
    public double millis(Duration cap) { return Math.max(1, Math.min(remaining().toMillis(), cap.toMillis())); }
    public void check() { remaining(); }
}
