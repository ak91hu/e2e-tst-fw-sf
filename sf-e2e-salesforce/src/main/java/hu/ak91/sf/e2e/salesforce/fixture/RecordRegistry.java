package hu.ak91.sf.e2e.salesforce.fixture;

import hu.ak91.sf.e2e.core.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static hu.ak91.sf.e2e.salesforce.fixture.OwnedRecord.State.*;
public final class RecordRegistry {
    public record Manifest(int schemaVersion,String runId,String attemptId,String caseId,String orgAlias,String origin,String retention,List<OwnedRecord> records) {}
    private final String runId,attemptId,caseId,alias,origin;
    private final Path path;
    private final List<OwnedRecord> records=new ArrayList<>();
    private static final Map<String,String> PREFIXES=Map.of("Account","001","Opportunity","006","Contract","800","Quote","0Q0","Product2","01t","Pricebook2","01s","Case","500");
    public RecordRegistry(FrameworkConfig config,String runId,String attemptId,String caseId) {
        this.runId=SafePaths.segment(runId);this.attemptId=SafePaths.segment(attemptId);this.caseId=SafePaths.segment(caseId);alias=config.org().alias();origin=config.org().baseUrl();
        Path root=Path.of(config.retention().journalDir()); path=SafePaths.inside(root,alias+"/"+runId+"/"+attemptId+".json"); persist();
    }
    public OwnedRecord claim(String object,String name,OwnedRecord parent,Persona persona) {
        Failure.require(object!=null && object.matches("[A-Za-z][A-Za-z0-9_]{0,127}") && name.startsWith("E2E-TA-"),"RETENTION_VIOLATION","A valid object API name and unique ownership prefix are required");
        if(parent!=null) owned(parent);
        var record=new OwnedRecord();record.claimId=UUID.randomUUID().toString();record.objectType=object;record.plannedName=name;record.currentName=name;
        record.marker=name;record.persona=persona;record.parentClaimId=parent==null?null:parent.claimId;
        record.nameHistory.add(name);record.markerHistory.add(name);records.add(record);persist();return record;
    }
    public void owned(OwnedRecord record) { Failure.require(records.contains(record) && record.state!=ABSENT_UI_VERIFIED,"RECORD_SCOPE_VIOLATION","Record is not owned by this attempt"); }
    public void prepared(OwnedRecord record) { owned(record);Failure.require(record.state==CLAIMED,"RETENTION_VIOLATION","Form state invalid");record.state=FORM_PREPARED;persist(); }
    public void saveRequested(OwnedRecord record) {
        owned(record);
        Failure.require(Set.of(FORM_PREPARED,SAVED,VERIFIED,RENAMED_VERIFIED,MARKER_CHANGED_VERIFIED,RENAME_REQUESTED,MARKER_CHANGE_REQUESTED).contains(record.state),"ACTION_COMMIT_UNCERTAIN","Save is not allowed in the current journal state");
        record.state=SAVE_REQUESTED;persist();
    }
    public void uncertain(OwnedRecord record,String reason) { owned(record);record.state=SAVE_UNCERTAIN;record.uncertaintyReason=reason;persist(); }
    public void saved(OwnedRecord record,String id,String evidence) {
        owned(record);
        Failure.require(record.state==SAVE_REQUESTED || record.state==SAVE_UNCERTAIN,"RETENTION_VIOLATION","Record had no save intent");
        String prefix=PREFIXES.get(record.objectType);
        Failure.require(id.matches("[A-Za-z0-9]{15}(?:[A-Za-z0-9]{3})?") && (prefix==null || id.startsWith(prefix)) &&
            (record.recordId==null || record.recordId.equals(id)),"RECORD_SCOPE_VIOLATION","UI record identity mismatch");
        record.recordId=id;record.url=origin+"/lightning/r/"+record.objectType+"/"+id+"/view";record.state=SAVED;record.evidenceRefs.add(evidence);persist();
    }
    public void verified(OwnedRecord record,String name,String marker,String evidence) {
        owned(record);Failure.require(record.recordId!=null,"RECORD_SCOPE_VIOLATION","Verification requires a UI record ID");
        if(record.pendingName!=null) {
            Failure.require(record.pendingName.equals(name),"VALUE_MISMATCH","Rename did not persist");
            record.currentName=name;record.nameHistory.add(name);record.pendingName=null;record.state=RENAMED_VERIFIED;
        } else { Failure.require(record.currentName.equals(name),"VALUE_MISMATCH","Owned record name changed unexpectedly");record.state=VERIFIED; }
        if(record.pendingMarker!=null) {
            Failure.require(record.pendingMarker.equals(marker),"VALUE_MISMATCH","Marker change did not persist");
            record.marker=marker;record.markerHistory.add(marker);record.pendingMarker=null;record.state=MARKER_CHANGED_VERIFIED;
        }
        record.verifiedAt=Instant.now();record.evidenceRefs.add(evidence);persist();
    }
    public void rename(OwnedRecord record,String name) {
        owned(record);Failure.require(record.recordId!=null && name.startsWith("E2E-TA-"),"RECORD_SCOPE_VIOLATION","Rename requires an owned saved record and E2E prefix");
        record.pendingName=name;record.state=RENAME_REQUESTED;persist();
    }
    public void markerChange(OwnedRecord record,String marker) {
        owned(record);Failure.require(record.recordId!=null && marker.startsWith("E2E-TA-"),"RECORD_SCOPE_VIOLATION","Marker requires an owned saved record and E2E prefix");
        record.pendingMarker=marker;record.state=MARKER_CHANGE_REQUESTED;persist();
    }
    public void absent(OwnedRecord record,String evidence) {
        owned(record);Failure.require(record.recordId==null && Set.of(CLAIMED,FORM_PREPARED,SAVE_REQUESTED,SAVE_UNCERTAIN).contains(record.state),"RETENTION_VIOLATION","An existing record cannot become an absent candidate");
        record.state=ABSENT_UI_VERIFIED;record.evidenceRefs.add(evidence);persist();
    }
    public void cancelIntent(OwnedRecord record) {
        owned(record);Failure.require(record.recordId!=null && record.state!=SAVE_UNCERTAIN,"ACTION_COMMIT_UNCERTAIN","Cannot discard uncertain saved intent");
        record.pendingName=null;record.pendingMarker=null;record.state=VERIFIED;persist();
    }
    public void child(String object,OwnedRecord parent,String exactIdentity,String relatedUrl,String evidence,Persona persona) {
        owned(parent);Failure.require(parent.recordId!=null && relatedUrl.startsWith(origin+"/lightning/r/"+parent.objectType+"/"+parent.recordId+"/"),"RECORD_SCOPE_VIOLATION","Child provenance must identify its owned parent");
        var child=new OwnedRecord();child.claimId=UUID.randomUUID().toString();child.objectType=object;child.parentClaimId=parent.claimId;
        child.plannedName=exactIdentity;child.currentName=exactIdentity;child.displayName=exactIdentity;child.url=relatedUrl;child.persona=persona;
        child.state=VERIFIED;child.evidenceRefs.add(evidence);child.verifiedAt=Instant.now();records.add(child);persist();
    }
    public OwnedRecord claimChild(String object,OwnedRecord parent,String identity,String relatedUrl,Persona persona) {
        owned(parent);parent.id();
        Failure.require(object!=null && object.matches("[A-Za-z][A-Za-z0-9_]{0,127}") && relatedUrl.startsWith(origin+"/lightning/r/"+parent.objectType+"/"+parent.id()+"/related/"),"RECORD_SCOPE_VIOLATION","Invalid child record provenance");
        var child=new OwnedRecord();child.claimId=UUID.randomUUID().toString();child.objectType=object;child.parentClaimId=parent.claimId;
        child.plannedName=identity;child.currentName=identity;child.displayName=identity;child.url=relatedUrl;child.persona=persona;child.state=FORM_PREPARED;records.add(child);persist();return child;
    }
    public void verifyChild(OwnedRecord child,String evidence) {
        owned(child);Failure.require(child.recordId==null && child.parentClaimId!=null && child.state==SAVE_REQUESTED,"RETENTION_VIOLATION","Child save intent missing");
        child.state=VERIFIED;child.verifiedAt=Instant.now();child.evidenceRefs.add(evidence);persist();
    }
    public Manifest manifest() { return new Manifest(1,runId,attemptId,caseId,alias,origin,"permanent",List.copyOf(records)); }
    public void persist() { Json.write(path,manifest()); }
    public String attemptId() { return attemptId; }
    public Path journalPath() { return path; }
}
