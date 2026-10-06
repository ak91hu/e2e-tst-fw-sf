package hu.ak91.sf.e2e.agent;
import hu.ak91.sf.e2e.core.*;
import java.util.*;
import java.util.function.Function;
public final class ScriptedModelClient implements ModelClient {
    private final Function<Request,Response> script;
    private int calls;
    public ScriptedModelClient(Function<Request,Response> script) { this.script=script; }
    public Response complete(Request request,Deadline deadline) { deadline.check(); calls++; return script.apply(request); }
    public String identity() { return "scripted-json-v1"; }
    public int calls() { return calls; }
}
