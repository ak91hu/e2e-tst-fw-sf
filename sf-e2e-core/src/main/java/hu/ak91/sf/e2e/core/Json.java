package hu.ak91.sf.e2e.core;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Json {
    public static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule())
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private Json() {}
    public static String string(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (IOException e) { throw new Failure("SERIALIZATION_FAILED", "Could not serialize structured data"); }
    }
    public static <T> T read(Path path, Class<T> type) {
        try { return MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8), type); }
        catch (IOException e) { throw new Failure("INVALID_SCHEMA", "Could not read " + path.getFileName()); }
    }
    public static void write(Path path, Object value) {
        Path temporary = null;
        try {
            Path parent = path.toAbsolutePath().getParent(); Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".write-", ".tmp");
            Files.writeString(temporary, string(value), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new Failure("JOURNAL_WRITE_FAILED", "Atomic write failed for " + path.getFileName()); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String hash(Object value) { return hash(string(value).getBytes(StandardCharsets.UTF_8)); }
}
