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

# ═══════════════════════════════════════════════════════════════
#  Gallery module — keep everything intact
# ═══════════════════════════════════════════════════════════════

-keep class vaddempudi.gnanesh.syntaxcli.gallery.** { *; }
-keep interface vaddempudi.gnanesh.syntaxcli.gallery.** { *; }

-keep class vaddempudi.gnanesh.syntaxcli.gallery.GalleryActivity { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.GalleryAdapter { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.GalleryAdapter$* { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.AlbumAdapter { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.AlbumAdapter$* { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.FullscreenAdapter { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.FullscreenAdapter$* { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.GalleryMediaItem { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.GalleryIndexCache { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.GalleryIndexCache$* { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.GalleryRepository { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.GalleryRepository$* { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.MediaStorePager { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.MediaStorePager$* { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.ThumbnailCache { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.HexRingDrawable { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.CustomVideoView { *; }
-keep class vaddempudi.gnanesh.syntaxcli.gallery.ZoomableImageView { *; }

# ═══════════════════════════════════════════════════════════════
#  AndroidX RecyclerView + ViewPager2 — protect against R8
#  class-merging that breaks instanceof/cast checks at runtime.
#  This is the fix for "ClassCastException: p2.n0 cannot be cast
#  to n0.g".
# ═══════════════════════════════════════════════════════════════

-keep class androidx.recyclerview.widget.** { *; }
-keep interface androidx.recyclerview.widget.** { *; }
-dontwarn androidx.recyclerview.widget.**

-keep class androidx.viewpager2.** { *; }
-keep interface androidx.viewpager2.** { *; }
-dontwarn androidx.viewpager2.**

-keep class androidx.recyclerview.widget.DiffUtil { *; }
-keep class androidx.recyclerview.widget.DiffUtil$* { *; }
-keep class androidx.recyclerview.widget.AsyncListDiffer { *; }
-keep class androidx.recyclerview.widget.AsyncListDiffer$* { *; }
-keep class androidx.recyclerview.widget.AsyncDifferConfig { *; }
-keep class androidx.recyclerview.widget.AsyncDifferConfig$* { *; }
-keep class androidx.recyclerview.widget.AdapterListUpdateCallback { *; }
-keep class androidx.recyclerview.widget.ListUpdateCallback { *; }

# ═══════════════════════════════════════════════════════════════
#  Kill R8's class-merging pass. This is the specific optimization
#  that produces "cannot be cast to" crashes in release builds
#  that work fine in debug.
# ═══════════════════════════════════════════════════════════════


-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations