package hu.ak91.sf.e2e.core.scenario;

import hu.ak91.sf.e2e.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Reviewed file-to-dataset coverage. Unknown changes fail closed to the full suite, never to a green empty run. */
public final class ChangeImpact {
    private ChangeImpact() {}
    public record Rule(String component,List<String> paths,Set<String> datasets) {
        public Rule {
            SafePaths.segment(component);Failure.require(paths!=null && !paths.isEmpty() && paths.size()<=100 && datasets!=null && !datasets.isEmpty() && datasets.size()<=500,"IMPACT_INVALID","Coverage rules need paths and datasets");
            paths.forEach(ChangeImpact::pattern);datasets.forEach(SafePaths::segment);paths=List.copyOf(paths);datasets=Set.copyOf(datasets);
        }
    }
    public record Coverage(int schemaVersion,String revision,List<String> globalPaths,Set<String> always,List<Rule> rules) {
        public Coverage {
            SafePaths.segment(revision);Failure.require(schemaVersion==1 && globalPaths!=null && globalPaths.size()<=100 && always!=null && always.size()<=500 && rules!=null && rules.size()<=1000 && rules.stream().allMatch(Objects::nonNull),"IMPACT_INVALID","Invalid reviewed coverage document");
            globalPaths.forEach(ChangeImpact::pattern);always.forEach(SafePaths::segment);globalPaths=List.copyOf(globalPaths);always=Set.copyOf(always);rules=List.copyOf(rules);
            Failure.require(rules.stream().map(Rule::component).distinct().count()==rules.size(),"IMPACT_INVALID","Coverage component names must be unique");
            Failure.require(rules.stream().mapToInt(r -> r.paths().size()).sum()+globalPaths.size()<=2000,"IMPACT_INVALID","Coverage exceeds 2000 patterns");
        }
    }
    public record Changes(int schemaVersion,List<String> files) {
        public Changes {Failure.require(schemaVersion==1 && files!=null && files.size()<=10000,"IMPACT_INVALID","Changes need at most 10000 repository-relative paths");files.forEach(ChangeImpact::path);files=files.stream().distinct().sorted().toList();}
    }
    public record Plan(int schemaVersion,String decision,String suiteChecksum,String coverageChecksum,String changesChecksum,
                       List<String> selected,List<String> directlyImpacted,List<String> unknown,List<String> uncoveredDatasets,
                       Map<String,List<String>> reasons) {}
    public static <T> T read(Path file,Class<T> type) {
        try {Failure.require(Files.isRegularFile(file) && Files.size(file)<=1024*1024,"IMPACT_INVALID","Impact input must be a bounded JSON file");return Json.read(file,type);}
        catch(Failure f) {throw f;}catch(Exception e) {throw new Failure("IMPACT_INVALID","Cannot read impact input");}
    }
    public static Plan evaluate(CaseSuite suite,Coverage coverage,Changes changes) {
        Map<String,CaseSuite.Row> rows=new LinkedHashMap<>();suite.cases().forEach(row -> rows.put(row.id(),row));
        Set<String> covered=new HashSet<>(coverage.always());coverage.rules().forEach(rule -> covered.addAll(rule.datasets()));
        Failure.require(rows.keySet().containsAll(covered),"IMPACT_INVALID","Coverage references a dataset outside the suite");
        List<String> uncovered=rows.keySet().stream().filter(id -> !covered.contains(id)).toList();
        Set<String> direct=new LinkedHashSet<>(),unknown=new TreeSet<>();Map<String,List<String>> reasons=new TreeMap<>();boolean global=false;
        var globalMatchers=coverage.globalPaths().stream().map(ChangeImpact::compile).toList();Map<Rule,List<Pattern>> matchers=new LinkedHashMap<>();coverage.rules().forEach(rule -> matchers.put(rule,rule.paths().stream().map(ChangeImpact::compile).toList()));
        for(String file:changes.files()) {
            boolean matched=false;
            if(globalMatchers.stream().anyMatch(glob -> glob.matcher(file).matches())) {global=true;matched=true;}
            for(var rule:coverage.rules()) if(matchers.get(rule).stream().anyMatch(glob -> glob.matcher(file).matches())) {
                matched=true;for(String id:rows.keySet()) if(rule.datasets().contains(id)) {direct.add(id);reasons.computeIfAbsent(id,k -> new ArrayList<>()).add("component:"+rule.component());}
            }
            if(!matched) unknown.add(file);
        }
        String decision=changes.files().isEmpty()?"NO_CHANGES":global || !unknown.isEmpty()?"FULL_SUITE":"IMPACTED";
        Set<String> wanted=new LinkedHashSet<>(direct);
        if(!changes.files().isEmpty()) {
            for(String id:rows.keySet()) if(coverage.always().contains(id) || uncovered.contains(id)) {wanted.add(id);reasons.computeIfAbsent(id,k -> new ArrayList<>()).add(coverage.always().contains(id)?"always":"uncovered-dataset");}
            if(decision.equals("FULL_SUITE")) {wanted.addAll(rows.keySet());for(String id:rows.keySet()) reasons.computeIfAbsent(id,k -> new ArrayList<>()).add(global?"global-change":"unknown-change");}
        }
        LinkedHashSet<String> ordered=new LinkedHashSet<>();for(String id:wanted) include(id,rows,ordered,reasons);
        Map<String,List<String>> explanation=new TreeMap<>();reasons.forEach((id,values) -> explanation.put(id,values.stream().distinct().sorted().toList()));
        return new Plan(1,decision,Json.hash(suite),Json.hash(coverage),Json.hash(changes),List.copyOf(ordered),List.copyOf(direct),List.copyOf(unknown),uncovered,Collections.unmodifiableMap(explanation));
    }
    private static void include(String id,Map<String,CaseSuite.Row> rows,Set<String> selected,Map<String,List<String>> reasons) {
        if(selected.contains(id)) return;
        for(String dependency:rows.get(id).after()) {reasons.computeIfAbsent(dependency,k -> new ArrayList<>()).add("dependency:"+id);include(dependency,rows,selected,reasons);}
        selected.add(id);
    }
    private static void path(String value) {
        Failure.require(value!=null && value.length()<=1000 && !value.isBlank() && !value.startsWith("/") && !value.contains("\\") && !value.contains(":") && !value.matches(".*[\\x00-\\x1f*?].*") && Arrays.stream(value.split("/",-1)).noneMatch(p -> p.isBlank() || p.equals(".") || p.equals("..")),"IMPACT_INVALID","Use safe repository-relative forward-slash paths");
    }
    private static void pattern(String glob) {Failure.require(glob!=null && glob.length()<=1000 && !glob.isBlank(),"IMPACT_INVALID","Invalid coverage pattern");path(glob.replace("*","x").replace("?","x"));}
    public static boolean matches(String glob,String file) {
        path(file);return compile(glob).matcher(file).matches();
    }
    private static Pattern compile(String glob) {
        pattern(glob);StringBuilder expression=new StringBuilder("^");
        for(int i=0;i<glob.length();i++) {
            char c=glob.charAt(i);
            if(c=='*' && i+1<glob.length() && glob.charAt(i+1)=='*') {i++;if(i+1<glob.length() && glob.charAt(i+1)=='/') {i++;expression.append("(?:.*/)?");}else expression.append(".*");}
            else if(c=='*') expression.append("[^/]*");else if(c=='?') expression.append("[^/]");else expression.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(expression.append('$').toString());
    }
}
