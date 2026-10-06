package hu.ak91.sf.e2e.core;

public final class Budget {
    private final int maxActions, maxCalls;
    private final long maxTokens;
    private int actions, calls;
    private long tokens;
    private boolean usageUnknown;
    public Budget(int maxActions, int maxCalls, long maxTokens) {
        Failure.require(maxActions > 0 && maxCalls > 0 && maxTokens > 0, "CONFIG_INVALID", "Budgets must be positive");
        this.maxActions = maxActions; this.maxCalls = maxCalls; this.maxTokens = maxTokens;
    }
    public void action() { Failure.require(actions < maxActions, "STEP_BUDGET_EXHAUSTED", "Action budget exhausted"); actions++; }
    public void call() { Failure.require(calls < maxCalls && tokens < maxTokens, "STEP_BUDGET_EXHAUSTED", "Model budget exhausted"); calls++; }
    public void usage(Long count) {
        if (count == null) usageUnknown = true;
        else { Failure.require(count >= 0, "MODEL_OUTPUT_INVALID", "Invalid usage"); tokens += count;
            Failure.require(tokens <= maxTokens, "STEP_BUDGET_EXHAUSTED", "Token budget exhausted"); }
    }
    public int actions() { return actions; }
    public int calls() { return calls; }
    public Long tokens() { return usageUnknown ? null : tokens; }
}
