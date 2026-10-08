package hu.ak91.sf.e2e.cli;
import hu.ak91.sf.e2e.core.*;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
@Command(name="session",mixinStandardHelpOptions=true,subcommands=SessionCommands.Encrypt.class,description="Protect an authorized external session; no login or plaintext export.")
public final class SessionCommands {
    @Command(name="encrypt",mixinStandardHelpOptions=true,description="Encrypt authorized Playwright state with AES-256-GCM and origin/lifetime binding.")
    public static final class Encrypt implements Callable<Integer> {
        @Option(names="--file",required=true) Path file;
        @Option(names="--output",required=true) Path output;
        @Option(names="--key-ref",required=true,description="Environment variable containing a base64-encoded random 32-byte key.") String keyRef;
        @Option(names="--origin",required=true,description="Exact authorized origin; repeatable.") List<String> origins;
        @Option(names="--ttl-hours",defaultValue="8") int hours;
        public Integer call() {
            Failure.require(!Files.exists(output) && hours>=1 && hours<=168,"POLICY_DENIED","Use a new encrypted output and 1..168 hour lifetime");
            var secrets=new SecretRegistry();String state=BrowserState.load(file,origins,secrets);
            Json.write(output,Json.MAPPER.readTree(SessionVault.seal(state,keyRef,SessionVault.key(keyRef,secrets),origins,Duration.ofHours(hours),Clock.systemUTC())));
            System.out.println("Encrypted session: "+output.toAbsolutePath()+"; lifetime hours="+hours);return 0;
        }
    }
}
