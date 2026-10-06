package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
public final class EvidenceService implements EvidenceSink {
    public record Artifact(String evidenceId,String runId,String attemptId,String path,String mediaType,String sha256,
        String redactionStatus,Instant capturedAt,String stage,String provenance) {}
    private final Path runRoot,attemptRoot;
    private final String runId,attemptId;
    private final Supplier<? extends UiEngine> engine;
    private final Deadline deadline;
    private final SecretRegistry secrets;
    private final List<Artifact> artifacts=new ArrayList<>();
    private final List<Map<String,Object>> events=new ArrayList<>();
    private final boolean screenshotsAfterSteps;
    private boolean insideCaseStep;
    public EvidenceService(Path runRoot,String runId,String attemptId,Supplier<? extends UiEngine> engine,Deadline deadline,SecretRegistry secrets) {
        this(runRoot,runId,attemptId,engine,deadline,secrets,true);
    }
    public EvidenceService(Path runRoot,String runId,String attemptId,Supplier<? extends UiEngine> engine,Deadline deadline,SecretRegistry secrets,boolean screenshotsAfterSteps) {
        this.runRoot=runRoot;this.runId=runId;this.attemptId=attemptId;this.engine=engine;this.deadline=deadline;this.secrets=secrets;
        this.screenshotsAfterSteps=screenshotsAfterSteps;
        attemptRoot=SafePaths.inside(runRoot,"attempts/"+SafePaths.segment(attemptId));
        try { Files.createDirectories(attemptRoot.resolve("artifacts")); } catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot create attempt artifact directory"); }
    }
    public static void validatePng(byte[] bytes) {
        Failure.require(bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}),"PNG_INVALID","Invalid PNG signature");
        try { BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));
            Failure.require(image!=null && image.getWidth()>0 && image.getHeight()>0,"PNG_INVALID","PNG cannot be decoded");
        } catch(IOException e) { throw new Failure("PNG_INVALID","PNG cannot be decoded"); }
    }
    @Override public String checkpoint(String stage) {
        SafePaths.segment(stage);UiEngine active=engine.get();Failure.require(active!=null,"EVIDENCE_WITHHELD","No active browser to capture");
        byte[] bytes=hu.ak91.sf.e2e.core.quality.ExecutionMetrics.time("masked-screenshot",artifacts.size()+1,this::event,() -> active.captureMaskedPng(deadline));validatePng(bytes);
        String id=UUID.randomUUID().toString(),relative="attempts/"+attemptId+"/artifacts/"+String.format(Locale.ROOT,"%03d",artifacts.size()+1)+"-"+stage+".png";
        Path path=SafePaths.inside(runRoot,relative);
        try { Files.write(path,bytes,StandardOpenOption.CREATE_NEW); } catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot persist PNG checkpoint"); }
        var artifact=new Artifact(id,runId,attemptId,relative,"image/png",Json.hash(bytes),"SAFE",Instant.now(),stage,"Playwright masked capture "+id);
        artifacts.add(artifact);Json.write(attemptRoot.resolve("artifact-manifest.json"),artifacts);
        event(Map.of("kind","checkpoint","evidenceId",id,"stage",stage));return id;
    }
    @Override public void event(Map<String,Object> event) {
        Map<String,Object> safe;
        try {
            safe=Json.MAPPER.convertValue(secrets.redactTree(Json.MAPPER.valueToTree(event)),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});
        } catch(Exception e) { throw new Failure("EVIDENCE_INCOMPLETE","Could not redact event"); }
        Map<String,Object> withTime=new LinkedHashMap<>(safe);withTime.put("capturedAt",Instant.now().toString());events.add(Map.copyOf(withTime));
        try { Files.writeString(attemptRoot.resolve("events.redacted.jsonl"),Json.string(withTime)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
        catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot write attempt events"); }
        if("phase-timing".equals(event.get("kind"))) Json.write(attemptRoot.resolve("timing-summary.json"),Map.of("runId",runId,"attemptId",attemptId,"durationNanosByPhase",hu.ak91.sf.e2e.core.quality.ExecutionMetrics.totals(events)));
        String kind=Objects.toString(event.get("kind"),"");
        if(Set.of("case-step-started","discovery-page-started").contains(kind)) insideCaseStep=true;
        boolean completed=Set.of("case-step-passed","case-step-learned","page-discovered").contains(kind);
        boolean agentCompleted=kind.equals("act") || Set.of("assert","wait","extract").contains(kind) && "PASSED".equals(event.get("verdict"));
        if(screenshotsAfterSteps && (completed || agentCompleted || Set.of("locator-action","assertion-passed").contains(kind) && !insideCaseStep)) checkpoint("step-"+(artifacts.size()+1));
        if(completed) insideCaseStep=false;
    }
    public void verifyMinimum(int required) {
        Failure.require(artifacts.size()>=required && artifacts.stream().anyMatch(a -> a.stage().equals("test-completion")),
            "EVIDENCE_INCOMPLETE","Passed attempt requires two distinct captures and a completion checkpoint");
        for(var artifact:artifacts) verifyArtifact(runRoot,artifact,runId,attemptId);
    }
    public static void verifyArtifact(Path root,Artifact artifact,String run,String attempt) {
        Failure.require(run.equals(artifact.runId()) && attempt.equals(artifact.attemptId()) && artifact.path().startsWith("attempts/"+attempt+"/artifacts/") &&
            artifact.redactionStatus().equals("SAFE") && artifact.mediaType().equals("image/png"),"REPORT_RUN_MISMATCH","Artifact ownership or redaction mismatch");
        try {
            byte[] bytes=Files.readAllBytes(SafePaths.inside(root,artifact.path()));validatePng(bytes);
            Failure.require(Json.hash(bytes).equals(artifact.sha256()),"PNG_INVALID","PNG integrity hash mismatch");
        } catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Artifact missing or unreadable"); }
    }
    public List<Artifact> artifacts() { return List.copyOf(artifacts); }
    public Path attemptRoot() { return attemptRoot; }
    public List<Map<String,Object>> events() { return List.copyOf(events); }
}
