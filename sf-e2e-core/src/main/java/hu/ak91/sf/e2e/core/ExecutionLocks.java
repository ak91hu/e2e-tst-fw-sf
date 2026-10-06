package hu.ak91.sf.e2e.core;

import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;

/** Shared org lease plus exclusive identity lease. Legacy exclusive-org runs cannot overlap a suite worker. */
public final class ExecutionLocks implements AutoCloseable {
    private static final Map<String,OrgLease> ORGS=new HashMap<>();
    private static final class OrgLease { final FileChannel channel;final FileLock lock;int references=1;OrgLease(FileChannel c,FileLock l) {channel=c;lock=l;} }
    private final String org;
    private final FileChannel identityChannel;
    private final FileLock identityLock;
    private boolean closed;
    private ExecutionLocks(String org,FileChannel channel,FileLock lock) {this.org=org;identityChannel=channel;identityLock=lock;}
    public static synchronized ExecutionLocks shared(String origin,String identity) {
        String org=Json.hash(origin);Path root=Path.of(System.getProperty("user.home"),".sf-e2e","locks");FileChannel channel=null;FileLock userLock=null;
        try {
            Files.createDirectories(root);var lease=ORGS.get(org);
            if(lease==null) {var orgChannel=FileChannel.open(root.resolve(org+".lock"),StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock lock;try {lock=orgChannel.tryLock(0,Long.MAX_VALUE,true);}catch(IOException | RuntimeException e) {orgChannel.close();throw e;}if(lock==null) {orgChannel.close();throw new Failure("ENVIRONMENT_UNAVAILABLE","An exclusive local run holds this org");}ORGS.put(org,new OrgLease(orgChannel,lock));}
            else lease.references++;
            channel=FileChannel.open(root.resolve(org+"-identity-"+Json.hash(identity)+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);userLock=channel.tryLock();Failure.require(userLock!=null,"ENVIRONMENT_UNAVAILABLE","Another local run holds this authorized identity");return new ExecutionLocks(org,channel,userLock);
        }catch(IOException | RuntimeException e) {if(channel!=null) try {channel.close();}catch(IOException ignored) {}releaseOrg(org);throw e instanceof Failure f?f:new Failure("ENVIRONMENT_UNAVAILABLE","Could not acquire bounded org/identity execution leases");}
    }
    private static void releaseOrg(String org) {var lease=ORGS.get(org);if(lease!=null && --lease.references==0) {ORGS.remove(org);try {lease.lock.release();}catch(IOException ignored) {}try {lease.channel.close();}catch(IOException ignored) {}}}
    public void close() {synchronized(ExecutionLocks.class) {if(!closed) {closed=true;try {identityLock.release();}catch(IOException ignored) {}try {identityChannel.close();}catch(IOException ignored) {}releaseOrg(org);}}}
}
