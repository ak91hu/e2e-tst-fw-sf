package hu.ak91.sf.e2e.core;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.io.IOException;
import java.util.*;

/** Raw traces/downloads are confidential. They are deliberately excluded from public Allure attachments. */
public final class PrivateArtifacts {
    private PrivateArtifacts() {}
    public static Path create(Path directory,String attemptId,int retentionHours) {
        SafePaths.segment(attemptId);Failure.require(retentionHours>=1 && retentionHours<=168,"CONFIG_INVALID","Private artifacts need 1..168 hour retention");
        try { Files.createDirectories(directory);Path real=directory.toRealPath();Failure.require(!Files.isSymbolicLink(directory) && real.getParent().equals(directory.toAbsolutePath().getParent().toRealPath()),"POLICY_DENIED","Private artifact directory cannot redirect outside its owned parent");
            Path policyFile=real.resolve("private-policy.json");if(Files.exists(policyFile)) {var existing=Json.read(policyFile,Map.class);Failure.require(attemptId.equals(existing.get("attemptId")) && "CONFIDENTIAL_RAW".equals(existing.get("classification")) && !Instant.now().isAfter(Instant.parse(existing.get("expiresAt").toString())),"REPORT_RUN_MISMATCH","Private directory belongs to another or expired attempt");}
            else try(var contents=Files.list(real)) {Failure.require(contents.findAny().isEmpty(),"POLICY_DENIED","A private artifact directory must be new or already owned");}
            var posix=Files.getFileAttributeView(real,PosixFileAttributeView.class);if(posix!=null) posix.setPermissions(PosixFilePermissions.fromString("rwx------"));
            else { var acl=Files.getFileAttributeView(real,AclFileAttributeView.class);Failure.require(acl!=null,"POLICY_DENIED","Private artifacts require enforceable owner-only permissions");var owner=Files.getOwner(real);acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT).build())); }
            if(!Files.exists(policyFile)) Json.write(policyFile,Map.of("schemaVersion",1,"attemptId",attemptId,"classification","CONFIDENTIAL_RAW","expiresAt",Instant.now().plus(Duration.ofHours(retentionHours)),"publishToAllure",false));return real;
        } catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot enforce private artifact permissions"); }
    }
    public static void manifest(Path directory,String attemptId,Path file,long maximumBytes) {
        try { var policy=Json.read(directory.resolve("private-policy.json"),Map.class);Failure.require(attemptId.equals(policy.get("attemptId")) && !Instant.now().isAfter(Instant.parse(policy.get("expiresAt").toString())) && file.toRealPath().getParent().equals(directory.toRealPath()) && Files.size(file)<=maximumBytes,"EVIDENCE_INCOMPLETE","Private artifact ownership, lifetime or size is invalid");
            Json.write(directory.resolve(file.getFileName()+".manifest.json"),Map.of("attemptId",attemptId,"path",file.getFileName().toString(),"sha256",Json.hash(Files.readAllBytes(file)),"bytes",Files.size(file),"classification","CONFIDENTIAL_RAW"));
        } catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Private artifact cannot be verified"); }
    }
    /** Explicit maintenance deletes only expired files listed by owned manifests, never whole directories. */
    public static int purgeExpired(Path directory) {
        int deleted=0;var policy=Json.read(directory.resolve("private-policy.json"),Map.class);if(Instant.now().isBefore(Instant.parse(policy.get("expiresAt").toString()))) return 0;
        try(var files=Files.newDirectoryStream(directory,"*.manifest.json")) { for(Path manifest:files) { var data=Json.read(manifest,Map.class);Failure.require(data.get("attemptId").equals(policy.get("attemptId")),"REPORT_RUN_MISMATCH","Private manifest ownership mismatch");Path file=SafePaths.inside(directory,data.get("path").toString());Failure.require(file.toRealPath().getParent().equals(directory.toRealPath()) && Json.hash(Files.readAllBytes(file)).equals(data.get("sha256")),"REPORT_RUN_MISMATCH","Private artifact identity changed");Files.delete(file);Files.delete(manifest);deleted++; }return deleted; }
        catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Expired private artifacts cannot be purged"); }
    }
}
