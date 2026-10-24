package org.junit.jupiter.api;
import java.util.Objects;
public final class Assertions {
    public interface Executable { void execute() throws Throwable; }
    public static void assertTrue(boolean c) { if (!c) throw new AssertionError("expected true"); }
    public static void assertTrue(boolean c, String m) { if (!c) throw new AssertionError(m); }
    public static void assertFalse(boolean c) { if (c) throw new AssertionError("expected false"); }
    public static void assertFalse(boolean c, String m) { if (c) throw new AssertionError(m); }
    public static void assertEquals(Object e, Object a) { if (!Objects.equals(e, a)) throw new AssertionError("expected " + e + " but was " + a); }
    public static void assertEquals(int e, int a) { if (e != a) throw new AssertionError("expected " + e + " but was " + a); }
    public static void assertEquals(long e, long a) { if (e != a) throw new AssertionError("expected " + e + " but was " + a); }
    public static void assertEquals(Object e, Object a, String m) { if (!Objects.equals(e, a)) throw new AssertionError(m + ": expected " + e + " but was " + a); }
    public static void assertEquals(int e, int a, String m) { if (e != a) throw new AssertionError(m + ": expected " + e + " but was " + a); }
    public static void assertEquals(long e, long a, String m) { if (e != a) throw new AssertionError(m + ": expected " + e + " but was " + a); }
    // floating-point overloads with a tolerance (same semantics as JUnit 5: |expected - actual| <= delta, NaN only equals NaN)
    private static boolean near(double e, double a, double d) { return Double.compare(e, a) == 0 || Math.abs(e - a) <= d; }
    public static void assertEquals(double e, double a, double d) { if (!near(e, a, d)) throw new AssertionError("expected " + e + " but was " + a + " (delta " + d + ")"); }
    public static void assertEquals(double e, double a, double d, String m) { if (!near(e, a, d)) throw new AssertionError(m + ": expected " + e + " but was " + a + " (delta " + d + ")"); }
    public static void assertEquals(float e, float a, float d) { if (!near(e, a, d)) throw new AssertionError("expected " + e + " but was " + a + " (delta " + d + ")"); }
    public static void assertEquals(float e, float a, float d, String m) { if (!near(e, a, d)) throw new AssertionError(m + ": expected " + e + " but was " + a + " (delta " + d + ")"); }
    public static void assertArrayEquals(double[] e, double[] a, double d) {
        if (e.length != a.length) throw new AssertionError("array length differs: " + e.length + " vs " + a.length);
        for (int i = 0; i < e.length; i++) if (!near(e[i], a[i], d)) throw new AssertionError("arrays differ at [" + i + "]: expected " + e[i] + " but was " + a[i]);
    }
    public static void assertArrayEquals(float[] e, float[] a, float d) {
        if (e.length != a.length) throw new AssertionError("array length differs: " + e.length + " vs " + a.length);
        for (int i = 0; i < e.length; i++) if (!near(e[i], a[i], d)) throw new AssertionError("arrays differ at [" + i + "]: expected " + e[i] + " but was " + a[i]);
    }
    public static void assertNotNull(Object o) { if (o == null) throw new AssertionError("expected non-null"); }
    public static void assertNull(Object o, String m) { if (o != null) throw new AssertionError(m + ": expected null but was " + o); }
    public static void assertNull(Object o) { if (o != null) throw new AssertionError("expected null but was " + o); }
    public static <T extends Throwable> T assertThrows(Class<T> t, Executable ex) {
        try { ex.execute(); } catch (Throwable x) { if (t.isInstance(x)) return t.cast(x); throw new AssertionError("wrong exception " + x); }
        throw new AssertionError("expected " + t.getSimpleName());
    }
    public static void assertArrayEquals(byte[] e, byte[] a) { if (!java.util.Arrays.equals(e, a)) throw new AssertionError("byte arrays differ"); }
    public static void assertArrayEquals(byte[] e, byte[] a, String m) { if (!java.util.Arrays.equals(e, a)) throw new AssertionError(m + ": byte arrays differ"); }
    public static void assertNotNull(Object o, String m) { if (o == null) throw new AssertionError(m + ": expected non-null"); }
    public static <T> T fail(String m) { throw new AssertionError(m); }
}
