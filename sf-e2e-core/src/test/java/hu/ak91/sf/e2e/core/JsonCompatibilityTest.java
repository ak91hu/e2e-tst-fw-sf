package hu.ak91.sf.e2e.core;

import hu.ak91.sf.e2e.core.scenario.CaseRecipe;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JsonCompatibilityTest {
    @TempDir Path directory;
    // Captured using the previously published Jackson 2.18.3 CLI, before this migration.
    private static final String LEGACY="""
        {"recipe":{"schemaVersion":1,"id":"TST_legacy_recipe","namespaceHash":"b9f3eb9fab4d478c718dfb9a39e7ca92f786dee1fc09da0cce8b774932a0df2b","engineVersion":"1.63.0","startRoute":"/lightning/page/home","steps":[{"kind":"expectVisible","page":"home","key":"result","route":null,"valueRef":null,"recordNameRef":null}]},"checksum":"e24149a5aa82a04976980f1b4e18b8227bf03d96674c659da79a98f83755a04b"}
        """.strip();
    record LegacyDefaults(int count,Instant timestamp,Map<String,Integer> values) {}
    @Test void previouslyLearnedRecipeRetainsChecksumAndExactSerialization() throws Exception {
        Path file=directory.resolve("TST_legacy_recipe.json");Files.writeString(file,LEGACY);
        var recipe=CaseRecipe.load(file);assertEquals("TST_legacy_recipe",recipe.id());
        recipe.save(file);assertEquals(LEGACY,Files.readString(file));
    }
    @Test void dateMapOrderingAndOptionalPrimitiveDefaultsRemainCompatible() throws Exception {
        var value=new LegacyDefaults(0,Instant.parse("2026-10-06T00:00:00Z"),Map.of("z",2,"a",1));
        assertEquals("{\"count\":0,\"timestamp\":\"2026-10-06T00:00:00Z\",\"values\":{\"a\":1,\"z\":2}}",Json.string(value));
        assertEquals(0,Json.MAPPER.readValue("{}",LegacyDefaults.class).count());
    }
    @Test void malformedOrUnknownStructuredDataKeepsThePublicFailureContract() throws Exception {
        Path file=directory.resolve("invalid.json");Files.writeString(file,"{\"unexpected\":true}");
        assertEquals("INVALID_SCHEMA",assertThrows(Failure.class,() -> Json.read(file,LegacyDefaults.class)).code());
        Files.writeString(file,"{malformed");
        assertEquals("INVALID_SCHEMA",assertThrows(Failure.class,() -> Json.read(file,LegacyDefaults.class)).code());
    }
}
