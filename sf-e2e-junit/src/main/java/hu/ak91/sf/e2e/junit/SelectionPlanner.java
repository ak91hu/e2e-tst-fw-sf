package hu.ak91.sf.e2e.junit;

import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;
public final class SelectionPlanner {
    public record Discovered(CaseInfo info,Method method) {}
    public static List<Discovered> discover(List<Class<?>> classes) {
        Map<String,Discovered> cases=new TreeMap<>();
        for(Class<?> type:classes) for(Method method:type.getDeclaredMethods()) {
            SfCase annotation=method.getAnnotation(SfCase.class);
            if(annotation==null) continue;
            Failure.require(method.isAnnotationPresent(Test.class) && method.getParameterCount()==1 && method.getParameterTypes()[0]==SfTestContext.class,
                "CONFIG_INVALID","Every case must be a JUnit test taking SfTestContext");
            var info=new CaseInfo(annotation.id(),annotation.name(),Persona.valueOf(annotation.persona()),List.of(annotation.tags()),List.of(annotation.dependencies()),type.getName()+"#"+method.getName());
            Failure.require(cases.put(info.id(),new Discovered(info,method))==null,"CONFIG_INVALID","Duplicate case ID: "+info.id());
        }
        for(var item:cases.values()) for(String dependency:item.info().dependencies())
            Failure.require(cases.containsKey(dependency) && !dependency.equals(item.info().id()),"CONFIG_INVALID","Missing or self setup dependency");
        return List.copyOf(cases.values());
    }
    public static List<Discovered> plan(List<Discovered> catalog,String profile,String caseId,String tag) {
        Map<String,Discovered> all=new TreeMap<>();catalog.forEach(c -> all.put(c.info().id(),c));
        Failure.require(caseId==null || all.containsKey(caseId),"CONFIG_INVALID","Unknown case ID: "+caseId);
        Predicate<Discovered> profileFilter=switch(profile) {
            case "full-parity" -> c -> true;
            case "regression","deterministic" -> c -> !c.info().tags().contains("ai");
            case "ai-live","ai-cache" -> c -> c.info().tags().contains("ai");
            case "smoke" -> c -> c.info().tags().contains("smoke");
            case "checks","mapping" -> c -> false;
            default -> throw new Failure("CONFIG_INVALID","Unknown execution profile");
        };
        Set<String> selected=new TreeSet<>();
        for(var item:catalog) if(profileFilter.test(item) && (caseId==null || item.info().id().equals(caseId)) &&
            (tag==null || item.info().tags().contains(tag))) selected.add(item.info().id());
        List<Discovered> ordered=new ArrayList<>();Set<String> done=new HashSet<>(),visiting=new HashSet<>();
        for(String id:selected) visit(id,all,visiting,done,ordered,Set.of("deterministic","regression").contains(profile));
        return executionOrder(ordered);
    }
    private static void visit(String id,Map<String,Discovered> all,Set<String> visiting,Set<String> done,List<Discovered> out,boolean noModel) {
        if(done.contains(id)) return;
        Failure.require(visiting.add(id),"CONFIG_INVALID","Setup dependency cycle");
        var entry=all.get(id);Failure.require(entry!=null,"CONFIG_INVALID","Missing dependency: "+id);
        Failure.require(!noModel || !entry.info().tags().contains("ai"),"CONFIG_INVALID","No-model profile cannot include AI dependency: "+id);
        for(String dependency:entry.info().dependencies()) visit(dependency,all,visiting,done,out,noModel);
        visiting.remove(id);done.add(id);out.add(entry);
    }
    /** Contiguous class groups preserve Jupiter class lifecycle, with dependency order inside each group. */
    public static List<Discovered> executionOrder(List<Discovered> selected) {
        Map<String,Discovered> all=new LinkedHashMap<>();
        selected.forEach(c -> Failure.require(all.put(c.info().id(),c)==null,"CONFIG_INVALID","Duplicate selected case ID"));
        List<Discovered> ordered=new ArrayList<>();Set<String> visiting=new HashSet<>(),done=new HashSet<>();
        for(String id:all.keySet()) visit(id,all,visiting,done,ordered,false);
        Map<Class<?>,List<Discovered>> groups=new LinkedHashMap<>();
        Map<Class<?>,Set<Class<?>>> dependencies=new LinkedHashMap<>();
        for(var entry:ordered) {
            Class<?> type=entry.method().getDeclaringClass();
            var customOrder=type.getAnnotation(TestMethodOrder.class);
            Failure.require(customOrder==null || customOrder.value()==DependencyMethodOrderer.class,"CONFIG_INVALID",
                "SfCase dependencies determine method order; remove conflicting @TestMethodOrder from "+type.getName());
            groups.computeIfAbsent(type,k -> new ArrayList<>()).add(entry);
            var parents=dependencies.computeIfAbsent(type,k -> new LinkedHashSet<>());
            for(String id:entry.info().dependencies()) {
                Class<?> parent=all.get(id).method().getDeclaringClass();if(parent!=type) parents.add(parent);
            }
        }
        List<Class<?>> classes=new ArrayList<>();Set<Class<?>> active=new HashSet<>(),finished=new HashSet<>();
        for(Class<?> type:groups.keySet()) visitClass(type,dependencies,active,finished,classes);
        return classes.stream().flatMap(type -> groups.get(type).stream()).toList();
    }
    private static void visitClass(Class<?> type,Map<Class<?>,Set<Class<?>>> dependencies,Set<Class<?>> active,Set<Class<?>> done,List<Class<?>> out) {
        if(done.contains(type)) return;
        Failure.require(active.add(type),"CONFIG_INVALID","Dependencies require interleaving test classes; move the related cases into one class or remove the class dependency cycle");
        for(Class<?> parent:dependencies.get(type)) visitClass(parent,dependencies,active,done,out);
        active.remove(type);done.add(type);out.add(type);
    }
}
