package hu.ak91.sf.e2e.reporting;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.salesforce.fixture.RecordRegistry;
import java.time.*;
import java.util.*;
public record AttemptResult(String runId,String attemptId,CaseInfo caseInfo,Instant startedAt,Instant completedAt,
    String businessVerdict,String evidenceVerdict,String status,String errorCode,String errorMessage,
    List<String> secondaryErrors,List<EvidenceService.Artifact> artifacts,List<Map<String,Object>> events,
    RecordRegistry.Manifest retainedRecords) {
    public long durationMillis() { return startedAt==null || completedAt==null?0:Duration.between(startedAt,completedAt).toMillis(); }
}
