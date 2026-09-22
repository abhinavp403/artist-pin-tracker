# R8 rules for the release build.
#
# Deliberately short. Every library here that needs rules ships them as consumer rules inside its
# own artifact — Room, OkHttp, Retrofit, Coil, Media3, Maps, Credential Manager and WorkManager all
# do, including the ones that instantiate classes reflectively by name (WorkManager keeps the names
# of ListenableWorker subclasses, which is what makes SyncWorker survive obfuscation). Adding
# blanket -keep rules "to be safe" on top of those is how shrinking quietly stops shrinking, so
# anything added below should name the thing it protects and why.

# Crash traces that name a file and a line, without which an obfuscated stack trace from a release
# build is unreadable. Line numbers are not de-obfuscation: keep the mapping file that every
# release build writes to app/build/outputs/mapping/release/ if you ever want to read one properly.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ktor-client-okhttp 3.1.3 (supabase-kt's engine) drags in okhttp-sse 4.12.0, which references
# okhttp3.internal.Util — a class OkHttp 5 deleted. Gradle resolves OkHttp itself to 5.x, so the
# class genuinely doesn't exist. The reference is only reachable through Ktor's SSE plugin, which
# nothing here installs; debug builds have run with this exact mismatch all along. If SSE is ever
# added, this becomes a real NoClassDefFoundError and okhttp-sse needs aligning to 5.x instead.
-dontwarn okhttp3.internal.Util

# kotlinx.serialization. The compiler plugin generates a serializer for every @Serializable class
# and looks it up through the class's Companion; R8 sees no caller for those members and would
# strip them, which surfaces as a SerializationException at runtime rather than at build time.
# These are the rules from the kotlinx.serialization README.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
