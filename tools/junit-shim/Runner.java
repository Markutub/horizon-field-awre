import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
public class Runner {
    public static void main(String[] a) throws Exception {
        Path root = Paths.get(a[0]);
        int pass = 0, fail = 0;
        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(root)) { s.filter(p -> p.toString().endsWith("Test.class")).forEach(files::add); }
        Collections.sort(files);
        for (Path p : files) {
            String cn = root.relativize(p).toString().replace('/', '.').replaceAll("\\.class$", "");
            Class<?> c = Class.forName(cn);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(org.junit.jupiter.api.Test.class)) continue;
                m.setAccessible(true);
                var ctor = c.getDeclaredConstructor(); ctor.setAccessible(true);
                try { m.invoke(ctor.newInstance()); pass++; System.out.println("PASS " + c.getSimpleName() + "." + m.getName()); }
                catch (Throwable t) { fail++; Throwable r = t.getCause() != null ? t.getCause() : t; System.out.println("FAIL " + c.getSimpleName() + "." + m.getName() + " -> " + r); }
            }
        }
        System.out.println("passed=" + pass + " failed=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
