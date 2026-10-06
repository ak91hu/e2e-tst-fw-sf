package hu.ak91.sf.e2e.salesforce.fixture;
import hu.ak91.sf.e2e.core.*;
import java.time.*;
import java.util.*;
public final class OwnedRecord {
    public enum State { CLAIMED, FORM_PREPARED, SAVE_REQUESTED, SAVE_UNCERTAIN, SAVED, VERIFIED, ABSENT_UI_VERIFIED, RENAME_REQUESTED, RENAMED_VERIFIED, MARKER_CHANGE_REQUESTED, MARKER_CHANGED_VERIFIED }
    public String claimId,objectType,plannedName,currentName,displayName,marker,parentClaimId,recordId,url,uncertaintyReason,pendingName,pendingMarker;
    public Persona persona;
    public State state=State.CLAIMED;
    public Instant createdAt=Instant.now(),verifiedAt;
    public List<String> nameHistory=new ArrayList<>(),markerHistory=new ArrayList<>(),evidenceRefs=new ArrayList<>();
    public OwnedRecord() {}
    public String name() { return currentName; }
    public String id() { Failure.require(recordId!=null,"RECORD_SCOPE_VIOLATION","Record ID has not been UI verified"); return recordId; }
}
