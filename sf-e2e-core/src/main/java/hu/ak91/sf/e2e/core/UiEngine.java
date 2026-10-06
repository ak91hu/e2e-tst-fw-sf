package hu.ak91.sf.e2e.core;

import java.util.*;
public interface UiEngine extends AutoCloseable {
    void open(String route, Deadline deadline);
    String route();
    Observation observe(String container, Deadline deadline);
    Observation.Node resolve(SemanticTarget target, Observation observation);
    /** Explicit read-only href routes for exploration. Unsupported engines expose no destinations. */
    default Map<String,String> navigationRoutes(Observation observation,Deadline deadline) { return Map.of(); }
    /** NODE_STALE may be thrown only before dispatch, when no UI interaction has been attempted.
     * All post-dispatch failures (including unknown commit state) must use a different code. */
    void perform(Action action, Observation observation, Deadline deadline);
    Map<String, String> readVisibleFields(Deadline deadline);
    byte[] captureMaskedPng(Deadline deadline);
    void poll(Deadline deadline);
    String capabilityVersion();
    default void switchPage(String handle,Deadline deadline) { throw new Failure("AUTOMATION_UNSUPPORTED","Page switching is unavailable in this backend"); }
    /** Start after authentication so login traffic cannot masquerade as an application defect. */
    default void startQualityMonitoring() {}
    /** Bounded, redacted metadata; never request/response bodies, credentials or raw query strings. */
    default List<hu.ak91.sf.e2e.core.quality.BrowserQuality.Issue> browserIssues() { return List.of(); }
    @Override void close();
    record Action(String kind, String nodeId, String value) {}
}
