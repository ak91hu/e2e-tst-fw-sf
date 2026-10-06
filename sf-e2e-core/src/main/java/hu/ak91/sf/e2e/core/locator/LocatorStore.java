package hu.ak91.sf.e2e.core.locator;

import hu.ak91.sf.e2e.core.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Namespaced, checksummed, bounded JSON repository with locked atomic read/modify/write. */
public final class LocatorStore {
    public record Document(int schemaVersion,String namespace,Map<String,LocatorEntry> entries,String checksum) {}
    private record Snapshot(String contentHash,Map<String,LocatorEntry> entries) {}
    private static final int MAX_BYTES=4*1024*1024;
    private Snapshot snapshot;
    private final Path file;
    private final String namespace,mode;
    public LocatorStore(Path dir,String namespace,String mode) {
        Failure.require(Set.of("read-write","read-only").contains(mode),"CONFIG_INVALID","Invalid locator store mode");
        this.namespace=namespace;this.mode=mode;file=dir.toAbsolutePath().resolve(Json.hash(namespace)+".json");
    }
    public Path path() { return file; }
    public boolean writable() { return mode.equals("read-write"); }
    public synchronized Map<String,LocatorEntry> entries() {
        if(!Files.exists(file)) { snapshot=null;return Map.of(); }
        try {
            // Read and hash bounded bytes every time: same-size/mtime edits must not evade integrity checks.
            byte[] bytes;
            try(var input=Files.newInputStream(file)) { bytes=input.readNBytes(MAX_BYTES+1); }
            Failure.require(bytes.length<=MAX_BYTES,"LOCATOR_INVALID","Locator repository exceeds 4 MiB");
            String contentHash=Json.hash(bytes);
            if(snapshot!=null && snapshot.contentHash().equals(contentHash)) return snapshot.entries();
            var doc=Json.MAPPER.readValue(bytes,Document.class);
            Failure.require(doc.schemaVersion()==1 && namespace.equals(doc.namespace()) && doc.entries()!=null && doc.entries().size()<=10000 &&
                Json.hash(doc.entries()).equals(doc.checksum()),"LOCATOR_INVALID","Invalid locator repository schema, namespace or checksum");
            doc.entries().forEach((id,e) -> Failure.require(id.equals(id(e.page(),e.key())),"LOCATOR_INVALID","Locator repository key mismatch"));
            var validated=Map.copyOf(doc.entries());snapshot=new Snapshot(contentHash,validated);return validated;
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("LOCATOR_INVALID","Cannot read locator repository"); }
    }
    private static String id(String page,String key) { return page+"/"+key; }
    public LocatorEntry get(String page,String key) {
        LocatorEntry entry=entries().get(id(page,key));
        Failure.require(entry!=null,"LOCATOR_NOT_MAPPED","Map the UI before using locator: "+page+"/"+key);
        return entry;
    }
    public void putAll(List<LocatorEntry> updates) {
        write(updates,null,null,null);
    }
    /** Full, explicit remapping replaces only this visible scope and archives its previous document. */
    public void replaceScope(String page,String scope,List<LocatorEntry> updates,Map<String,LocatorEntry> expected) {
        Failure.require(updates.stream().allMatch(e -> e.page().equals(page) && Objects.equals(scope,e.primary().container())),
            "LOCATOR_INVALID","Scope replacement contains locators from another page or scope");
        write(updates,page,scope,expected);
    }
    private void write(List<LocatorEntry> updates,String page,String scope,Map<String,LocatorEntry> expected) {
        Failure.require(writable(),"LOCATOR_READ_ONLY","Locator repository is read-only");
        if(updates.isEmpty() && expected==null) return;
        try {
            Files.createDirectories(file.getParent());
            try(var channel=FileChannel.open(file.resolveSibling(file.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.tryLock()) {
                Failure.require(lock!=null,"LOCATOR_STORE_BUSY","Another process is updating the locator repository");
                Map<String,LocatorEntry> entries=new TreeMap<>(entries());
                Map<String,LocatorEntry> before=new TreeMap<>(entries);
                if(expected!=null) {
                    var currentScope=scopeEntries(entries,page,scope);
                    Failure.require(currentScope.equals(scopeEntries(expected,page,scope)),"LOCATOR_CONFLICT","Mapped scope changed concurrently; discover it again");
                    Set<String> retained=new HashSet<>();updates.forEach(e -> retained.add(id(e.page(),e.key())));
                    currentScope.keySet().stream().filter(key -> !retained.contains(key)).forEach(entries::remove);
                }
                for(var entry:updates) {
                    var previous=entries.get(id(entry.page(),entry.key()));
                    Failure.require(previous==null || expected!=null && entry.equals(previous) || entry.revision()==previous.revision()+1,
                        "LOCATOR_CONFLICT","Locator changed concurrently; resolve it again");
                    entries.put(id(entry.page(),entry.key()),entry);
                }
                Failure.require(entries.size()<=10000,"LOCATOR_INVALID","Too many mapped locators");
                var document=new Document(1,namespace,entries,Json.hash(entries));
                Failure.require(Json.string(document).getBytes(StandardCharsets.UTF_8).length<=4*1024*1024,"LOCATOR_INVALID","Locator repository exceeds size limit");
                if(entries.equals(before)) return;
                if(expected!=null && !before.isEmpty()) Json.write(file.getParent().resolve("history").resolve(file.getFileName().toString()).resolve(Json.hash(before)+".json"),new Document(1,namespace,before,Json.hash(before)));
                Json.write(file,document);
            }
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("LOCATOR_STORE_BUSY","Could not lock or update locator repository"); }
    }
    private static Map<String,LocatorEntry> scopeEntries(Map<String,LocatorEntry> entries,String page,String scope) {
        Map<String,LocatorEntry> result=new TreeMap<>();
        entries.forEach((key,e) -> { if(e.page().equals(page) && Objects.equals(e.primary().container(),scope)) result.put(key,e); });
        return result;
    }
    public static String namespace(FrameworkConfig config,Persona persona) {
        String reference=config.persona(persona).usernameRef();String username=reference.isBlank()?null:System.getenv(reference);
        String identity=username==null || username.isBlank()?persona.name():Json.hash(username.toLowerCase(Locale.ROOT));
        return config.projectId()+"|"+config.org().alias()+"|"+config.org().baseUrl()+"|"+persona+"|"+identity+"|"+
            config.ui().language()+"|"+config.ui().locale()+"|"+config.ui().currency()+"|"+config.ui().personaTimeZone()+"|"+config.org().uiProfileVersion()+
            (config.ui().browser().equals("chromium") && config.ui().shadowPolicy().equals("cdp")?"":"|"+config.ui().browser()+"|"+config.ui().shadowPolicy());
    }
}
