package hu.ak91.sf.e2e.cli;

import hu.ak91.sf.e2e.core.Json;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class GuideCommandTest {
    @Test void guideIsReadableWithoutProjectConfigurationAndEveryListedTopicIsBundled() throws Exception {
        var cli=SfE2eCli.commandLine();var output=new StringWriter();cli.setOut(new PrintWriter(output));
        assertEquals(0,cli.execute("guide","--list"));var index=Json.MAPPER.readTree(output.toString());assertTrue(index.has("cases"));
        for(var topic:index.properties()) {
            output.getBuffer().setLength(0);assertEquals(0,cli.execute("guide",topic.getKey()));
            assertTrue(output.toString().startsWith("# "),"Bundled topics must contain actual Markdown, not generated help or paths");
        }
    }
    @Test void guideCannotReadArbitraryClasspathOrPrivateFiles() {
        var cli=SfE2eCli.commandLine();var errors=new StringWriter();cli.setErr(new PrintWriter(errors));
        assertEquals(2,cli.execute("guide","../../project.local.yaml"));assertTrue(errors.toString().contains("Unknown guide topic"));
    }
}
