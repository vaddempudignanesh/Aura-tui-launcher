-keep public class ohi.andre.consolelauncher.commands.main.raw.** { *; }
-keep public class ohi.andre.consolelauncher.commands.main.specific.** { *; }
-keep public class ohi.andre.consolelauncher.commands.tuixt.raw.** { *; }
-keep public class ohi.andre.consolelauncher.tuils.GenericFileProvider { *; }
-keep public class ohi.andre.consolelauncher.tuils.PrivateIOReceiver { *; }
-keep public class ohi.andre.consolelauncher.tuils.PublicIOReceiver { *; }
-keep class ohi.andre.consolelauncher.managers.** { *; }
-keep class ohi.andre.consolelauncher.tuils.libsuperuser.**
-keep class ohi.andre.consolelauncher.managers.suggestions.HideSuggestionViewValues
-keep public class it.andreuzzi.comparestring2.**

-dontwarn ohi.andre.consolelauncher.commands.main.raw.**

-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.Unsafe

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

-dontwarn org.htmlcleaner.**
-dontwarn com.jayway.jsonpath.**
-dontwarn org.slf4j.**

-dontwarn org.jdom2.**


# ═══════════════════════════════════════════════════════════════
#  Remove ALL android.util.Log calls from the release build.
#
#  `assumenosideeffects` tells R8 that these methods have no side
#  effects, so the entire call — including its argument expressions
#  (string concatenation, String.format, StringBuilder, etc.) — is
#  eliminated from the bytecode.
#
#  Do NOT apply this to the debug build, or you lose all logs while
#  developing. (See gradle snippet below.)
# ═══════════════════════════════════════════════════════════════
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
    public static *** isLoggable(...);
    public static *** getStackTraceString(...);
}

# Same treatment for the System.out / System.err paths some
# libraries use to print logs. Not strictly required, but tidy.
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
    public void printf(...);
    public void format(...);
}