package hu.ak91.sf.e2e.junit;

import org.junit.jupiter.api.*;
import java.util.*;

/** Uses the coordinator's validated plan, preserving one Jupiter class execution. */
public final class DependencyMethodOrderer implements MethodOrderer {
    @Override public void orderMethods(MethodOrdererContext context) {
        var session=RunSession.CURRENT.get();if(session==null) return;
        Map<String,Integer> ranks=new HashMap<>();
        for(int i=0;i<session.selection.size();i++) ranks.put(session.selection.get(i).id(),i);
        context.getMethodDescriptors().sort(Comparator.comparingInt(method -> {
            var annotation=method.getMethod().getAnnotation(SfCase.class);
            return annotation==null?Integer.MAX_VALUE:ranks.getOrDefault(annotation.id(),Integer.MAX_VALUE);
        }));
    }
}
