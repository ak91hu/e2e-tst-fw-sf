package hu.ak91.sf.e2e.agent;

import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.locator.*;
import hu.ak91.sf.e2e.core.scenario.CaseSpec;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Hashed intent associations only; the locator repository remains the sole control definition store. */
final class CaseTargetIndex {
    record Reference(String page,String key,String identity) {
        Reference {
            Failure.require(page!=null && page.matches("[a-zA-Z0-9_.-]{1,100}") && key!=null && key.matches("[a-zA-Z0-9_.-]{1,100}") &&
                identity!=null && identity.matches("[a-f0-9]{64}"),"CASE_TARGET_INDEX_INVALID","Invalid case target reference");
        }
    }
    record Document(int schemaVersion,String owner,Map<String,List<Reference>> bindings,String checksum) {}
    private final LocatorStore store;
    private final Path file;
    private String contentHash;
    private Map<String,List<Reference>> snapshot;
    CaseTargetIndex(LocatorStore store) {
        this.store=store;file=store.path().resolveSibling(store.path().getFileName()+".target-index.json");
    }
    static String identity(LocatorEntry entry) {
        return Json.hash(Map.of("route",entry.route(),"engine",entry.engineVersion(),"primary",entry.primary(),"attributes",entry.attributes()));
    }
    private static String intent(CaseSpec.Step step,String route,String engine) {
        return Json.hash(Map.of("route",route,"engine",engine,"scope",step.container(),"instruction",step.instruction().strip(),"recordTemplate",step.recordNameRef()!=null));
    }
    List<Reference> references(CaseSpec.Step step,String route,String engine) {
        return read().getOrDefault(intent(step,route,engine),List.of());
    }
    /** Carry hashed aliases only across a proven deterministic repair of the same shared key. */
    void rebind(LocatorEntry before,LocatorEntry after) {
        if(identity(before).equals(identity(after))) return;
        Failure.require(before.page().equals(after.page()) && before.key().equals(after.key()) && before.route().equals(after.route()) &&
            before.engineVersion().equals(after.engineVersion()) && after.revision()==before.revision()+1 && !after.history().isEmpty() &&
            after.history().getLast().before().equals(before.primary()) && after.history().getLast().after().equals(after.primary()) &&
            Set.of("fingerprint","stored-alternative").contains(after.history().getLast().reason()),
            "LOCATOR_CONFLICT","Intent aliases can follow only a proven deterministic locator repair");
        var old=new Reference(before.page(),before.key(),identity(before));
        if(read().values().stream().noneMatch(refs -> refs.contains(old))) return;
        Failure.require(store.writable(),"LOCATOR_READ_ONLY","Repaired aliases require a writable locator repository");
        try(var channel=FileChannel.open(file.resolveSibling(file.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.tryLock()) {
            Failure.require(lock!=null,"LOCATOR_STORE_BUSY","Another process is updating repaired intent aliases");
            Failure.require(store.get(after.page(),after.key()).equals(after),"LOCATOR_CONFLICT","Repaired control changed before alias publication");
            var replacement=new Reference(after.page(),after.key(),identity(after));var bindings=new TreeMap<String,List<Reference>>();
            read().forEach((id,refs) -> bindings.put(id,refs.stream().map(ref -> ref.equals(old)?replacement:ref).distinct().toList()));
            Json.write(file,new Document(1,store.path().getFileName().toString(),bindings,Json.hash(bindings)));
        } catch(Failure failure) {throw failure;}
        catch(Exception failure) {throw new Failure("LOCATOR_STORE_BUSY","Cannot lock or update repaired intent aliases");}
    }
    void remember(CaseSpec.Step step,LocatorEntry entry) {
        Failure.require(store.writable(),"LOCATOR_READ_ONLY","Case target associations require a writable locator repository");
        String id=intent(step,entry.route(),entry.engineVersion());var reference=new Reference(entry.page(),entry.key(),identity(entry));
        if(read().getOrDefault(id,List.of()).contains(reference)) return;
        try {
            Files.createDirectories(file.getParent());
            try(var channel=FileChannel.open(file.resolveSibling(file.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var lock=channel.tryLock()) {
                Failure.require(lock!=null,"LOCATOR_STORE_BUSY","Another process is updating case target associations");
                Failure.require(store.get(entry.page(),entry.key()).equals(entry),"LOCATOR_CONFLICT","Reused control changed before intent association publication");
                var bindings=new TreeMap<>(read());var refs=new ArrayList<>(bindings.getOrDefault(id,List.of()));
                if(refs.contains(reference)) return;
                refs.add(reference);bindings.put(id,List.copyOf(refs));
                Failure.require(bindings.size()<=10000 && refs.size()<=32,"CASE_TARGET_INDEX_INVALID","Case target association limit exceeded");
                var document=new Document(1,store.path().getFileName().toString(),bindings,Json.hash(bindings));
                Failure.require(Json.string(document).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4*1024*1024,
                    "CASE_TARGET_INDEX_INVALID","Case target associations exceed 4 MiB");
                Json.write(file,document);
            }
        } catch(Failure failure) { throw failure; }
        catch(Exception failure) { throw new Failure("LOCATOR_STORE_BUSY","Cannot lock or update case target associations"); }
    }
    private Map<String,List<Reference>> read() {
        if(!Files.exists(file)) {contentHash=null;snapshot=null;return Map.of();}
        try(var input=Files.newInputStream(file)) {
            byte[] bytes=input.readNBytes(4*1024*1024+1);
            Failure.require(bytes.length<=4*1024*1024,"CASE_TARGET_INDEX_INVALID","Case target associations exceed 4 MiB");
            String hash=Json.hash(bytes);if(hash.equals(contentHash)) return snapshot;
            var document=Json.MAPPER.readValue(bytes,Document.class);
            Failure.require(document.schemaVersion()==1 && store.path().getFileName().toString().equals(document.owner()) && document.bindings()!=null &&
                document.bindings().size()<=10000 && Json.hash(document.bindings()).equals(document.checksum()),"CASE_TARGET_INDEX_INVALID","Invalid case target association schema, owner or checksum");
            var validated=new TreeMap<String,List<Reference>>();
            document.bindings().forEach((key,refs) -> {
                Failure.require(key.matches("[a-f0-9]{64}") && refs!=null && !refs.isEmpty() && refs.size()<=32 && refs.stream().allMatch(Objects::nonNull) &&
                    new HashSet<>(refs).size()==refs.size(),"CASE_TARGET_INDEX_INVALID","Invalid case target associations");
                validated.put(key,List.copyOf(refs));
            });
            snapshot=Map.copyOf(validated);contentHash=hash;return snapshot;
        } catch(Failure failure) {throw failure;}
        catch(Exception failure) {throw new Failure("CASE_TARGET_INDEX_INVALID","Cannot read case target associations");}
    }
}
