package hu.ak91.sf.e2e.core;
import java.util.List;
public record CaseInfo(String id,String name,Persona persona,List<String> tags,List<String> dependencies,String source) {
    public CaseInfo { SafePaths.segment(id);tags=List.copyOf(tags);dependencies=List.copyOf(dependencies); }
}
