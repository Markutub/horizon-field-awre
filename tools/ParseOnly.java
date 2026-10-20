import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.nio.file.*;
import java.util.*;
public class ParseOnly {
    public static void main(String[] a) throws Exception {
        JavaCompiler c = ToolProvider.getSystemJavaCompiler();
        var fm = c.getStandardFileManager(null, null, null);
        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(Paths.get(a[0]))) { s.filter(p -> p.toString().endsWith(".java")).forEach(files::add); }
        DiagnosticCollector<JavaFileObject> d = new DiagnosticCollector<>();
        JavacTask t = (JavacTask) c.getTask(null, fm, d, List.of("-proc:none"), null, fm.getJavaFileObjectsFromPaths(files));
        t.parse();
        long errs = d.getDiagnostics().stream().filter(x -> x.getKind() == Diagnostic.Kind.ERROR).count();
        d.getDiagnostics().forEach(x -> System.out.println(x));
        System.out.println("files=" + files.size() + " syntax errors=" + errs);
    }
}
