package hu.ak91.sf.e2e.agent;
import hu.ak91.sf.e2e.core.Deadline;
import java.util.function.Supplier;
public final class ModelStepExecutor implements StepExecutor {
    private final Supplier<ModelClient> model;
    public ModelStepExecutor(Supplier<ModelClient> model) { this.model=model; }
    public ModelClient.Response next(ModelClient.Request request,Deadline deadline) { return model.get().complete(request,deadline); }
    public String identity() { return "model-json-v1"; }
}
