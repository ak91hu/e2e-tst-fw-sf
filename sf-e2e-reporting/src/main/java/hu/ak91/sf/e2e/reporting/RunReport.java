package hu.ak91.sf.e2e.reporting;
import hu.ak91.sf.e2e.core.*;
import java.time.*;
import java.util.*;
public record RunReport(int schemaVersion,String runId,Instant startedAt,Instant completedAt,List<CaseInfo> selection,
                        List<AttemptResult> results,boolean complete,int exitCode) {
    public Map<String,Long> counts() {
        Map<String,Long> counts=new TreeMap<>();for(var result:results) counts.merge(result.status(),1L,Long::sum);return counts;
    }
    public static int exitCode(List<AttemptResult> results) {
        if(results.stream().anyMatch(r -> r.status().equals("CANCELLED"))) return 130;
        if(results.stream().anyMatch(r -> r.status().equals("EVIDENCE_FAILED") || r.evidenceVerdict().equals("FAILED") || r.errorCode()!=null && Set.of("JOURNAL_WRITE_FAILED","PNG_INVALID","EVIDENCE_INCOMPLETE").contains(r.errorCode()))) return 4;
        if(results.stream().anyMatch(r -> "ENVIRONMENT_UNAVAILABLE".equals(r.errorCode()))) return 3;
        if(results.stream().anyMatch(r -> Set.of("BLOCKED","NOT_RUN_DEPENDENCY","SKIPPED").contains(r.status()))) return 2;
        if(results.stream().anyMatch(r -> !r.status().equals("PASSED"))) return 1;
        return 0;
    }
}
