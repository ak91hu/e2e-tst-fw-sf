package hu.ak91.sf.e2e.reporting;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.salesforce.fixture.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
public final class ReportIntegrityVerifier {
    private ReportIntegrityVerifier() {}
    public static void verify(Path root,RunReport report,int minimumPng) {
        Failure.require(report.schemaVersion()==1 && report.complete() && report.completedAt()!=null &&
            report.selection().size()==report.results().size(),"REPORT_RUN_MISMATCH","Run is incomplete or result count differs from selection");
        Set<String> selected=new HashSet<>(),seen=new HashSet<>();
        for(var item:report.selection()) Failure.require(selected.add(item.id()),"REPORT_RUN_MISMATCH","Duplicate selected ID");
        for(var result:report.results()) {
            Failure.require(result.runId().equals(report.runId()) && selected.contains(result.caseInfo().id()) && seen.add(result.caseInfo().id()),"REPORT_RUN_MISMATCH","Duplicate or foreign result");
            Set<String> ids=new HashSet<>(),paths=new HashSet<>(),provenance=new HashSet<>();
            for(var artifact:result.artifacts()) {
                EvidenceService.verifyArtifact(root,artifact,report.runId(),result.attemptId());
                Failure.require(ids.add(artifact.evidenceId()) && paths.add(artifact.path()) && provenance.add(artifact.provenance()),"EVIDENCE_INCOMPLETE","Duplicate capture provenance");
            }
            if(result.status().equals("PASSED")) {
                Failure.require(result.events().stream().noneMatch(e -> "quality-gate".equals(e.get("kind")) && "FAILED".equals(e.get("status")) ||
                    "browser-issue".equals(e.get("kind")) && Boolean.FALSE.equals(e.get("waived")) && Boolean.TRUE.equals(e.get("gateEnabled"))),"REPORT_RUN_MISMATCH","Passed result contains a failed application quality gate");
                Failure.require(result.businessVerdict().equals("PASSED") && result.evidenceVerdict().equals("PASSED") &&
                    result.artifacts().size()>=minimumPng && result.artifacts().stream().anyMatch(a -> a.stage().equals("test-completion")),
                    "EVIDENCE_INCOMPLETE","Passed result has incomplete evidence");
            }
            var manifest=result.retainedRecords();
            if(manifest==null) {
                boolean unstartedSkip=result.status().equals("SKIPPED") && result.startedAt()==null && result.businessVerdict().equals("NOT_RUN");
                Failure.require(Set.of("NOT_RUN_DEPENDENCY","BLOCKED","CANCELLED").contains(result.status()) || unstartedSkip,
                    "RETENTION_VIOLATION","Attempt lacks record manifest");continue;
            }
            Failure.require(manifest.runId().equals(report.runId()) && manifest.attemptId().equals(result.attemptId()) &&
                manifest.caseId().equals(result.caseInfo().id()) && manifest.retention().equals("permanent"),"REPORT_RUN_MISMATCH","Manifest ownership mismatch");
            var disk=Json.read(SafePaths.inside(root,"attempts/"+result.attemptId()+"/retained-records.json"),RecordRegistry.Manifest.class);
            Failure.require(Json.hash(disk).equals(Json.hash(manifest)),"RETENTION_VIOLATION","Manifest copy differs from reported journal");
            Set<String> claims=new HashSet<>();
            for(var record:manifest.records()) {
                Failure.require(claims.add(record.claimId),"RETENTION_VIOLATION","Duplicate record claim");
                if(record.recordId!=null) {
                    Failure.require(record.recordId.matches("[A-Za-z0-9]{15}(?:[A-Za-z0-9]{3})?") && record.currentName!=null &&
                        record.url.equals(manifest.origin()+"/lightning/r/"+record.objectType+"/"+record.recordId+"/view") &&
                        !record.evidenceRefs.isEmpty(),"RETENTION_VIOLATION","Saved record has no exact UI identity or evidence");
                } else if(record.state==OwnedRecord.State.VERIFIED) {
                    Failure.require(record.parentClaimId!=null && record.url!=null && record.url.startsWith(manifest.origin()+"/lightning/r/") &&
                        record.displayName!=null && !record.displayName.isBlank() && !record.evidenceRefs.isEmpty(),"RETENTION_VIOLATION","Child record lacks related-list provenance");
                }
                Failure.require(record.evidenceRefs.stream().allMatch(ids::contains),"EVIDENCE_INCOMPLETE","Journal evidence is not owned by the attempt");
                if(result.status().equals("PASSED")) Failure.require(!Set.of(OwnedRecord.State.CLAIMED,OwnedRecord.State.FORM_PREPARED,OwnedRecord.State.SAVE_REQUESTED,OwnedRecord.State.SAVE_UNCERTAIN,OwnedRecord.State.SAVED,OwnedRecord.State.RENAME_REQUESTED,OwnedRecord.State.MARKER_CHANGE_REQUESTED).contains(record.state),"RETENTION_VIOLATION","Passed attempt has unresolved record intent");
            }
            for(var record:manifest.records()) Failure.require(record.parentClaimId==null || claims.contains(record.parentClaimId),"RETENTION_VIOLATION","Record parent claim missing");
        }
        Failure.require(report.exitCode()==RunReport.exitCode(report.results()),"REPORT_RUN_MISMATCH","Stored exit code disagrees with result verdicts");
    }
}
