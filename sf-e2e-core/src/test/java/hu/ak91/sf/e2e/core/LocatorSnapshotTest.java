package hu.ak91.sf.e2e.core;

import hu.ak91.sf.e2e.core.locator.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocatorSnapshotTest {
    @TempDir Path root;
    LocatorStore store() {
        var store=new LocatorStore(root,"snapshot","read-write");
        store.putAll(List.of(new LocatorEntry("p","submit","https://org/app","engine",SemanticTarget.role("button","Submit","page"),Map.of("testId","submit"),List.of(),1,List.of())));
        return store;
    }
    @Test void unchangedContentReusesOneImmutableValidatedSnapshot() {
        var store=store();var first=store.entries();assertSame(first,store.entries());
        assertThrows(UnsupportedOperationException.class,() -> first.clear());
        assertThrows(UnsupportedOperationException.class,() -> first.get("p/submit").attributes().clear());
    }
    @Test void sameSizeAndRestoredTimestampCannotHideTampering() throws Exception {
        var store=store();store.entries();var timestamp=Files.getLastModifiedTime(store.path());String original=Files.readString(store.path());
        Files.writeString(store.path(),original.replace("Submit","Delete"));Files.setLastModifiedTime(store.path(),timestamp);
        assertEquals("LOCATOR_INVALID",assertThrows(Failure.class,store::entries).code());
        Files.writeString(store.path(),original);assertEquals("Submit",store.get("p","submit").primary().name());
    }
    @Test void externalValidUpdateDeletionAndRecreationInvalidateTheSnapshot() throws Exception {
        var reader=store();var first=reader.entries();var old=reader.get("p","submit");var writer=new LocatorStore(root,"snapshot","read-write");
        writer.putAll(List.of(new LocatorEntry(old.page(),old.key(),old.route(),old.engineVersion(),SemanticTarget.role("button","Send","page"),old.attributes(),List.of(),2,List.of())));
        assertNotSame(first,reader.entries());assertEquals(2,reader.get("p","submit").revision());
        Files.delete(reader.path());assertTrue(reader.entries().isEmpty());
        writer.putAll(List.of(old));assertEquals("Submit",reader.get("p","submit").primary().name());
    }
    @Test void oversizedOrMalformedReplacementCannotReuseValidatedData() throws Exception {
        var store=store();store.entries();Files.writeString(store.path()," ".repeat(4*1024*1024+1));
        assertEquals("LOCATOR_INVALID",assertThrows(Failure.class,store::entries).code());
        Files.writeString(store.path(),"{broken");assertEquals("LOCATOR_INVALID",assertThrows(Failure.class,store::entries).code());
    }
}
