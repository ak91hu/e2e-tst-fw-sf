package hu.ak91.sf.e2e.agent;
import hu.ak91.sf.e2e.core.*;
import java.net.http.HttpClient;
/** Test-only package bridge for explicit synthetic local-model credentials; absent from production artifacts. */
public final class FixtureModelClient {
    private FixtureModelClient() {}
    public static ModelClient local(FrameworkConfig.Agent policy,SecretRegistry secrets) {return new JsonModelClient(policy,secrets,HttpClient.newHttpClient(),"SYNTHETIC_MODEL_CREDENTIAL");}
}
