package hu.ak91.sf.e2e.engine;

import hu.ak91.sf.e2e.core.*;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReliabilityHarnessIT {
    private FrameworkConfig config(String browser,String shadow) {
        var c=FrameworkConfig.defaults();
        return new FrameworkConfig(1,"reliability","checks",c.org(),new FrameworkConfig.Ui("en","en-US","USD","UTC",800,600,browser,shadow,""),
            c.auth(),c.personas(),c.execution(),c.agent(),c.cache(),c.retention(),c.evidence(),c.report());
    }
    private Deadline deadline() { return Deadline.after(Duration.ofSeconds(60)); }

    @Test void closingRetiredRuntimeDoesNotRemoveItsReplacement() {
        var c=config("chromium","cdp");var retired=new PlaywrightEngine.WorkerRuntime(c);retired.close();
        try(var current=new PlaywrightEngine.WorkerRuntime(c)) {
            retired.close();
            assertEquals("ENGINE_THREAD_VIOLATION",assertThrows(Failure.class,() -> new PlaywrightEngine.WorkerRuntime(c)).code());
        } finally { PlaywrightEngine.closeWorkerRuntime(); }
    }

    @Test void failedBorrowedContextInitializationReleasesOnlyItsOwnContext() throws Exception {
        var c=config("firefox","cdp");
        try(var runtime=new PlaywrightEngine.WorkerRuntime(c)) {
            var field=PlaywrightEngine.WorkerRuntime.class.getDeclaredField("browser");field.setAccessible(true);
            var browser=(Browser)field.get(runtime);
            assertEquals(0,browser.contexts().size());
            assertEquals("ENVIRONMENT_UNAVAILABLE",assertThrows(Failure.class,() -> new PlaywrightEngine(c,new SecretRegistry(),deadline())).code());
            assertEquals(0,browser.contexts().size(),"A context created before the unsupported CDP operation must be released");
            try(var engine=new PlaywrightEngine(config("firefox","tracked"),new SecretRegistry(),deadline())) {
                engine.setHarnessHtml("<button>Fresh context</button>");
                assertEquals(1,browser.contexts().size());
            }
            assertEquals(0,browser.contexts().size());
        }
    }

    @ParameterizedTest @ValueSource(strings={"chromium","firefox","webkit"})
    void delayedMultiselectStateIsAwaitedWithoutRepeatingClicks(String browser) {
        var d=deadline();try(var engine=new PlaywrightEngine(config(browser,browser.equals("chromium")?"cdp":"tracked"),new SecretRegistry(),d)) {
            engine.setHarnessHtml("<button role=combobox aria-label=Choices aria-controls=owned>Choices</button>"+
                "<div id=owned role=listbox aria-multiselectable=true>"+
                "<div role=option aria-selected=false onclick=\"window.a=(window.a||0)+1;setTimeout(()=>this.setAttribute('aria-selected','true'),150)\">A</div>"+
                "<div role=option aria-selected=true onclick=\"window.b=(window.b||0)+1;setTimeout(()=>this.setAttribute('aria-selected','false'),150)\">B</div></div>");
            engine.action("multiSelect",SemanticTarget.label("Choices","page"),"[\"A\"]",d);
            engine.harnessScript("if(window.a!==1 || window.b!==1)throw Error('Selection dispatched more than once')");
        }
    }

    @Test void duplicateUnselectedOptionsAreRejectedBeforeAnySelectionMutation() {
        var d=deadline();try(var engine=new PlaywrightEngine(config("chromium","cdp"),new SecretRegistry(),d)) {
            engine.setHarnessHtml("<button role=combobox aria-label=Choices aria-controls=owned>Choices</button>"+
                "<div id=owned role=listbox aria-multiselectable=true>"+
                "<div role=option aria-selected=false onclick=\"window.mutated=1;this.setAttribute('aria-selected','true')\">A</div>"+
                "<div role=option aria-selected=false>B</div><div role=option aria-selected=false>B</div></div>");
            assertEquals("TARGET_AMBIGUOUS",assertThrows(Failure.class,() -> engine.action("multiSelect",SemanticTarget.label("Choices","page"),"[\"A\"]",d)).code());
            engine.harnessScript("if(window.mutated)throw Error('Invalid option set was mutated')");
        }
    }

    @Test void unchangedMultiselectStateFailsWithoutRepeatingTheMutation() {
        var c=config("chromium","cdp");var e=c.execution();
        c=new FrameworkConfig(1,c.projectId(),c.profile(),c.org(),c.ui(),c.auth(),c.personas(),
            new FrameworkConfig.Execution(e.workers(),e.retries(),e.testTimeoutSeconds(),1,e.assertionTimeoutSeconds(),e.headed()),
            c.agent(),c.cache(),c.retention(),c.evidence(),c.report());
        var d=deadline();try(var engine=new PlaywrightEngine(c,new SecretRegistry(),d)) {
            engine.setHarnessHtml("<button role=combobox aria-label=Choices aria-controls=owned>Choices</button>"+
                "<div id=owned role=listbox aria-multiselectable=true><div role=option aria-selected=false onclick='window.clicks=(window.clicks||0)+1'>A</div></div>");
            assertEquals("ASSERTION_FAILED",assertThrows(Failure.class,() -> engine.action("multiSelect",SemanticTarget.label("Choices","page"),"[\"A\"]",d)).code());
            engine.harnessScript("if(window.clicks!==1)throw Error('Failed action repeated')");
        }
    }

    @Test void actionTimeoutCannotOutliveTheAttemptEvenWithALongerCallerDeadline() {
        var c=config("chromium","cdp");
        try(var runtime=new PlaywrightEngine.WorkerRuntime(c)) {
            var attempt=Deadline.after(Duration.ofSeconds(2));
            try(var engine=new PlaywrightEngine(c,new SecretRegistry(),attempt)) {
                engine.setHarnessHtml("<button style='position:absolute;top:0;left:0;width:100px;height:50px'>Blocked</button>"+
                    "<div style='position:fixed;inset:0;z-index:10;background:white'></div>");
                long start=System.nanoTime();
                assertThrows(TimeoutError.class,() -> engine.action("click",SemanticTarget.role("button","Blocked","page"),null,Deadline.after(Duration.ofSeconds(30))));
                assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<5000,"Action must be bounded by the two-second attempt, rather than the longer caller/action timeout");
            }
        }
    }

    @Test void runtimeRefusesClosureWhileAContextIsStillActive() {
        var c=config("chromium","cdp");var d=deadline();
        try(var runtime=new PlaywrightEngine.WorkerRuntime(c)) {
            try(var engine=new PlaywrightEngine(c,new SecretRegistry(),d)) {
                assertEquals("ENGINE_THREAD_VIOLATION",assertThrows(Failure.class,runtime::close).code());
                engine.setHarnessHtml("<button>Still available</button>");
                assertNotNull(engine.resolve(SemanticTarget.role("button","Still available","page"),engine.observe("page",d)));
                engine.close();engine.close();
            }
        }
    }
}
