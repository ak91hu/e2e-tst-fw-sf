package hu.ak91.sf.e2e.junit;

import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.extension.*;
import java.util.*;
import org.opentest4j.TestAbortedException;
public final class SfE2eExtension implements BeforeAllCallback,BeforeEachCallback,AfterEachCallback,ParameterResolver {
    private static final ExtensionContext.Namespace NS=ExtensionContext.Namespace.create(SfE2eExtension.class);
    @Override public void beforeAll(ExtensionContext context) {
        Failure.require(RunSession.CURRENT.get()!=null,"CONFIG_INVALID",
            "Salesforce cases require RunCoordinator or CLI run to enforce dependencies, profiles and the org lock; direct JUnit execution is unsupported");
    }
    @Override public void beforeEach(ExtensionContext context) {
        var annotation=context.getRequiredTestMethod().getAnnotation(SfCase.class);
        Failure.require(annotation!=null,"CONFIG_INVALID","Missing stable SfCase annotation");
        var info=new CaseInfo(annotation.id(),annotation.name(),Persona.valueOf(annotation.persona()),List.of(annotation.tags()),List.of(annotation.dependencies()),context.getRequiredTestClass().getName()+"#"+context.getRequiredTestMethod().getName());
        RunSession session=RunSession.CURRENT.get();
        Failure.require(session!=null && session.selection.stream().anyMatch(c -> c.id().equals(info.id()) && c.source().equals(info.source())),
            "CONFIG_INVALID","Case is outside the coordinated selection");
        context.getStore(NS).put("session",session);
        if(Thread.currentThread().isInterrupted()) {
            session.notRun(info,"CANCELLED","CANCELLED","Run interrupted");throw new TestAbortedException("Run interrupted");
        }
        if(info.dependencies().stream().anyMatch(id -> !session.passed(id))) {
            session.notRun(info,"NOT_RUN_DEPENDENCY","SETUP_DEPENDENCY_FAILED","Required setup did not pass");
            throw new TestAbortedException("Required setup did not pass");
        }
        var sf=session.context(info);context.getStore(NS).put("sf",sf);
        sf.initialize();
    }
    @Override public boolean supportsParameter(ParameterContext parameter,ExtensionContext context) { return parameter.getParameter().getType()==SfTestContext.class; }
    @Override public Object resolveParameter(ParameterContext parameter,ExtensionContext context) { return context.getStore(NS).get("sf",SfTestContext.class); }
    @Override public void afterEach(ExtensionContext context) {
        RunSession session=context.getStore(NS).get("session",RunSession.class);SfTestContext sf=context.getStore(NS).get("sf",SfTestContext.class);
        if(session==null) return;
        String id=context.getRequiredTestMethod().getAnnotation(SfCase.class).id();
        if(sf!=null && !session.has(id)) {
            var error=context.getExecutionException().orElse(null);
            var result=sf.finish(error);session.accept(result);
            if(error==null && !result.status().equals("PASSED"))
                throw new Failure(result.errorCode()==null?"EVIDENCE_INCOMPLETE":result.errorCode(),
                    result.errorMessage()==null?"Attempt did not pass":result.errorMessage());
        }
    }
}
