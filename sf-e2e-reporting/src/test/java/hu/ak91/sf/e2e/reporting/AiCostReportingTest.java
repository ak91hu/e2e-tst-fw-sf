package hu.ak91.sf.e2e.reporting;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AiCostReportingTest {
    @SuppressWarnings("unchecked") @Test void latestSnapshotsPerAttemptAggregateWithoutDoubleCountingAndUnknownCostStaysUnknown() {
        var events=new ArrayList<Map<String,Object>>();
        events.add(Map.of("kind","ai-workflow-summary","workflowId","first","metrics",Map.of("providerCalls",1,"chargedTokens",10,"costRatesConfigured",true,"estimatedCostUsd",new BigDecimal("0.01"))));
        events.add(Map.of("kind","ai-workflow-summary","workflowId","first","metrics",Map.of("providerCalls",2,"chargedTokens",30,"costRatesConfigured",true,"estimatedCostUsd",new BigDecimal("0.03"),"inputBytesSent",2000,"compactBytesSaved",400,"outputTokensReserved",1536)));
        events.add(Map.of("kind","ai-workflow-summary","workflowId","second","metrics",Map.of("providerCalls",1,"chargedTokens",20,"costRatesConfigured",true,"estimatedCostUsd",new BigDecimal("0.02"))));
        var summary=(Map<String,Object>)QualityReports.summarize(events).get("aiWorkflow");assertEquals(3L,summary.get("providerCalls"));assertEquals(50L,summary.get("chargedTokens"));assertEquals(0,new BigDecimal("0.05").compareTo((BigDecimal)summary.get("estimatedCostUsd")));
        assertEquals(2000L,summary.get("inputBytesSent"));assertEquals(400L,summary.get("compactBytesSaved"));assertEquals(1536L,summary.get("outputTokensReserved"));
        events.add(Map.of("kind","ai-targets-batched"));events.add(Map.of("kind","ai-batch-target-reused"));events.add(Map.of("kind","ai-map-reused"));
        var optimized=(Map<?,?>)QualityReports.summarize(events).get("aiWorkflow");
        assertEquals(1L,optimized.get("batchedRequests"));assertEquals(1L,optimized.get("batchedTargetsReused"));assertEquals(1L,optimized.get("reusedScopes"));
        events.add(Map.of("kind","ai-workflow-summary","workflowId","third","metrics",Map.of("providerCalls",1,"chargedTokens",8000,"costRatesConfigured",false,"unknownUsageCalls",1)));
        assertNull(((Map<?,?>)QualityReports.summarize(events).get("aiWorkflow")).get("estimatedCostUsd"));
    }
}
