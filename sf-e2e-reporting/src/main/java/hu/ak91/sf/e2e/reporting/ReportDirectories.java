package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Timestamped, atomically reserved run directories shared by every reporting workflow. */
public final class ReportDirectories {
    private static final DateTimeFormatter TIMESTAMP=DateTimeFormatter
        .ofPattern("uuuu-MM-dd_HH-mm-ss.SSSSSSSSS'Z'",Locale.ROOT).withZone(ZoneOffset.UTC);
    private ReportDirectories() {}

    public static Path create(Path root,Instant startedAt) {
        String timestamp=TIMESTAMP.format(startedAt);
        try {
            Files.createDirectories(root);
            for(int ordinal=1;ordinal<=10000;ordinal++) {
                String name=timestamp+(ordinal==1?"":"_"+String.format(Locale.ROOT,"%04d",ordinal));
                Path directory=SafePaths.inside(root,name);
                try { return Files.createDirectory(directory); }
                catch(FileAlreadyExistsException collision) { /* Reserve the next suffix, never reuse old evidence. */ }
            }
            throw new Failure("EVIDENCE_INCOMPLETE","Report timestamp has too many directory collisions");
        } catch(IOException failure) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot reserve timestamped report directory"); }
    }
}
