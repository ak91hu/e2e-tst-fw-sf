package hu.ak91.sf.e2e.agent;
import hu.ak91.sf.e2e.core.Deadline;
/** Trusted Java executor SPI. It proposes responses; only the harness dispatches checked UI actions. */
public interface StepExecutor {
    ModelClient.Response next(ModelClient.Request request,Deadline deadline);
    String identity();
}
