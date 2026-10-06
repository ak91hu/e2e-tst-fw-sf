package hu.ak91.sf.e2e.core.scenario;

import hu.ak91.sf.e2e.core.Failure;
import java.nio.file.Path;

/** Naming policy for newly learned/discovered cases. Historical recipe IDs are never rewritten on load. */
public final class CaseNames {
    private CaseNames() {}
    public static String generated(String id) {
        Failure.require(id!=null && id.matches("[a-zA-Z0-9_.-]{1,70}"),"CASE_INVALID","Invalid case ID");
        String name=id.startsWith("TST_")?id:"TST_"+id;
        Failure.require(name.length()<=70 && name.length()>4,"CASE_INVALID","Case ID needs a nonempty TST_ suffix and at most 70 characters including the prefix");
        return name;
    }
    public static Path output(Path requested) {
        Failure.require(requested!=null && requested.getFileName()!=null,"CASE_INVALID","Recipe output needs a file name");
        String name=requested.getFileName().toString();
        return name.startsWith("TST_")?requested:requested.resolveSibling("TST_"+name);
    }
}
