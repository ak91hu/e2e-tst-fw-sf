package hu.ak91.sf.e2e.core;

import java.nio.file.*;
import java.io.IOException;
public final class SafePaths {
    private SafePaths() {}
    public static String segment(String value) {
        Failure.require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}") && !value.contains(".."),
            "PATH_DENIED", "Invalid artifact identifier");
        return value;
    }
    public static Path inside(Path root, String relative) {
        try {
            Path absoluteRoot = root.toAbsolutePath().normalize();
            Path target = absoluteRoot.resolve(relative).normalize();
            Failure.require(!Path.of(relative).isAbsolute() && target.startsWith(absoluteRoot),
                "PATH_DENIED", "Artifact escapes root");
            Path existing = target;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing != null && Files.exists(absoluteRoot))
                Failure.require(existing.toRealPath().startsWith(absoluteRoot.toRealPath()), "PATH_DENIED", "Symlink escapes artifact root");
            return target;
        } catch (IOException e) { throw new Failure("PATH_DENIED", "Cannot resolve artifact path"); }
    }
}
