package hu.ak91.sf.e2e.core.quality;

import hu.ak91.sf.e2e.core.*;
import java.util.*;
import java.util.function.Consumer;

/** Metadata-only observations. Timer publication is deliberately unable to replace an action verdict. */
public final class ExecutionMetrics {
    private ExecutionMetrics() {}
    public static String category(Throwable error) {
        if(error instanceof AssertionError) return "assertion";
        String code=error instanceof Failure f?f.code():"ENGINE_FAILURE";
        if(code.equals("CANCELLED")) return "cancellation";
        if(code.contains("ASSERTION") || code.equals("VALUE_MISMATCH") || code.equals("APPLICATION_DEFECT") || code.equals("QUALITY_GATE_FAILED")) return "assertion";
        if(code.startsWith("AUTH_") || code.startsWith("ENVIRONMENT") || code.equals("API_PERMISSION_DENIED")) return "environment";
        if(code.startsWith("EVIDENCE") || code.startsWith("PNG_") || code.startsWith("REPORT_") || code.startsWith("JOURNAL_") || code.equals("RETENTION_VIOLATION")) return "evidence";
        return "action";
    }
    public static void measure(String phase,int sequence,Consumer<Map<String,Object>> sink,Runnable action) {
        long start=System.nanoTime();Throwable failure=null;
        try { action.run(); } catch(RuntimeException | Error e) { failure=e;throw e; }
        finally { try { sink.accept(Map.of("kind","phase-timing","phase",phase,"sequence",sequence,"durationNanos",Math.max(0,System.nanoTime()-start),"outcome",failure==null?"PASSED":"FAILED","failureCategory",failure==null?"none":category(failure))); } catch(RuntimeException | Error ignored) {} }
    }
    public static <T> T time(String phase,int sequence,Consumer<Map<String,Object>> sink,java.util.function.Supplier<T> action) {
        var value=new java.util.concurrent.atomic.AtomicReference<T>();measure(phase,sequence,sink,() -> value.set(action.get()));return value.get();
    }
    public static Map<String,Long> totals(List<Map<String,Object>> events) {
        Map<String,Long> totals=new TreeMap<>();for(var e:events) if("phase-timing".equals(e.get("kind")) && e.get("durationNanos") instanceof Number n && n.longValue()>=0) totals.merge(Objects.toString(e.get("phase")),n.longValue(),Math::addExact);return Map.copyOf(totals);
    }
}
