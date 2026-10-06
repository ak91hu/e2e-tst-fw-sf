package hu.ak91.sf.e2e.core;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BrowserStateTest {
    @TempDir Path root;
    @Test void externalSessionIsValidatedRedactedAndNeverRewritten() throws Exception {
        var path=root.resolve("authorized-state.json");
        Json.write(path,Map.of("cookies",List.of(Map.of("name","sid","value","PRIVATE_SESSION_SENTINEL","domain","portal.company.example","path","/")),
            "origins",List.of(Map.of("origin","https://portal.company.example","localStorage",List.of(Map.of("name","auth","value","PRIVATE_STORAGE_SENTINEL"))))));
        byte[] before=Files.readAllBytes(path);var secrets=new SecretRegistry();
        assertTrue(BrowserState.load(path,List.of("https://portal.company.example"),secrets).contains("PRIVATE_SESSION_SENTINEL"));
        assertFalse(secrets.redact("PRIVATE_SESSION_SENTINEL PRIVATE_STORAGE_SENTINEL").contains("SENTINEL"));assertArrayEquals(before,Files.readAllBytes(path));
        assertEquals("AUTH_CREDENTIAL_INVALID",assertThrows(Failure.class,() -> BrowserState.load(path,List.of("https://another.company.example"),secrets)).code());
    }
    @Test void foreignStorageIsRejectedAndStructuredIndexedDbSecretsAreRegistered() {
        var path=root.resolve("state.json");
        Json.write(path,Map.of("cookies",List.of(),"origins",List.of(Map.of("origin","https://foreign.example","localStorage",List.of()))));
        assertThrows(Failure.class,() -> BrowserState.load(path,List.of("https://portal.example"),new SecretRegistry()));
        Json.write(path,Map.of("cookies",List.of(),"origins",List.of(Map.of("origin","https://portal.example","localStorage",List.of(),"indexedDB",List.of(Map.of("name","auth-db","records",List.of(Map.of("value",Map.of("token","PRIVATE_INDEXED_DB_TOKEN")))))))));
        var secrets=new SecretRegistry();assertTrue(BrowserState.load(path,List.of("https://portal.example"),secrets).contains("auth-db"));
        assertFalse(secrets.redact("PRIVATE_INDEXED_DB_TOKEN").contains("TOKEN"));
    }
}
