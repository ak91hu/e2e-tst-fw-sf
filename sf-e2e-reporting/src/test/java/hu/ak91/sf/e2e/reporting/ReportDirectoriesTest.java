package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.FrameworkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportDirectoriesTest {
    @TempDir Path root;
    private static final Instant TIME=Instant.parse("2026-10-06T08:38:32.122005600Z");

    @Test void defaultRootAndUtcNamesPreserveExactTimeAndSortChronologically() {
        assertEquals("report",FrameworkConfig.defaults().report().root());
        Path first=ReportDirectories.create(root,TIME),later=ReportDirectories.create(root,TIME.plusNanos(1));
        assertEquals("2026-10-06_08-38-32.122005600Z",first.getFileName().toString());
        assertTrue(first.getFileName().toString().compareTo(later.getFileName().toString())<0);
        assertEquals(root.toAbsolutePath(),first.getParent());assertTrue(Files.isDirectory(first));
    }
    @Test void collisionDoesNotReuseOrOverwriteEarlierRun() throws Exception {
        Path first=ReportDirectories.create(root,TIME);Files.writeString(first.resolve("index.html"),"retained report");
        Path second=ReportDirectories.create(root,TIME);
        assertEquals(first.getFileName()+"_0002",second.getFileName().toString());
        assertEquals("retained report",Files.readString(first.resolve("index.html")));assertFalse(Files.exists(second.resolve("index.html")));
    }
    @Test void concurrentRunsReserveDistinctDirectoriesForTheSameInstant() throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)) {
            List<Callable<Path>> calls=new ArrayList<>();for(int i=0;i<16;i++) calls.add(() -> ReportDirectories.create(root,TIME));
            Set<Path> paths=new HashSet<>();for(var result:executor.invokeAll(calls)) paths.add(result.get());
            assertEquals(16,paths.size());assertTrue(paths.stream().allMatch(Files::isDirectory));
            assertTrue(paths.stream().allMatch(path -> path.getParent().equals(root.toAbsolutePath())));
        }
    }
}
