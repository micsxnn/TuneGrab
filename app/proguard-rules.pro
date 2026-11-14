# Regras do R8 para o release do TuneGrab.
#
# Estratégia CONSERVADORA: o R8 só ENCOLHE o APK (remove código que ninguém
# alcança). Não ofusca e não otimiza, porque o app depende de reflexão e de
# código nativo (yt-dlp, NewPipe, Ktor, Jackson) e porque os relatórios de
# erro (CrashReportActivity) precisam de stack traces legíveis.
-dontobfuscate
-dontoptimize

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable

# --- Código do próprio app -----------------------------------------------
# Activities, serviços, ponte JS (@JavascriptInterface) e as funções JNI do
# Mp3Encoder são achadas por nome. O app é pequeno perto das libs: manter
# tudo dele não pesa e elimina uma classe inteira de bugs de runtime.
-keep class com.tunegrab.app.** { *; }

# --- Motor yt-dlp embutido (youtubedl-android) ---------------------------
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**
# Desserialização de JSON por reflexão (Jackson)
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# --- NewPipeExtractor (fork) e o que ele traz ----------------------------
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**

# --- Rede -----------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**

# Retrofit declara a API como interfaces lidas por reflexão
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# --- kotlinx.serialization ------------------------------------------------
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# --- Ktor / InnerTubeX (runtimeOnly) ---------------------------------------
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class com.metrolist.** { *; }
-dontwarn com.metrolist.**

# --- Compose / Backdrop / Media3 / Coil (trazem as próprias regras) -------
-dontwarn androidx.compose.**
-dontwarn io.github.kyant0.**
