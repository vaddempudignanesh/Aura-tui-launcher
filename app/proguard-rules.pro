# ═══════════════════════════════════════════════════════════════
#  Existing keep rules (unchanged)
# ═══════════════════════════════════════════════════════════════

-keep public class vaddempudi.gnanesh.syntaxcli.commands.main.raw.** { *; }
-keep public class vaddempudi.gnanesh.syntaxcli.commands.main.specific.** { *; }
-keep public class vaddempudi.gnanesh.syntaxcli.commands.tuixt.raw.** { *; }
-keep public class vaddempudi.gnanesh.syntaxcli.tuils.GenericFileProvider { *; }
-keep public class vaddempudi.gnanesh.syntaxcli.tuils.PrivateIOReceiver { *; }
-keep public class vaddempudi.gnanesh.syntaxcli.tuils.PublicIOReceiver { *; }
-keep class vaddempudi.gnanesh.syntaxcli.managers.** { *; }
-keep class vaddempudi.gnanesh.syntaxcli.tuils.libsuperuser.**
-keep class vaddempudi.gnanesh.syntaxcli.managers.suggestions.HideSuggestionViewValues
-keep public class it.andreuzzi.comparestring2.**

-dontwarn vaddempudi.gnanesh.syntaxcli.commands.main.raw.**

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
