# Nexconn Chat SDK: message types, adapters and the native bridge rely on reflection and JNI,
# and the SDK ships only minimal consumer rules, so keep it whole.
-keep class ai.nexconn.** { *; }
-keep interface ai.nexconn.** { *; }
-keep class io.rong.** { *; }
-dontwarn ai.nexconn.**
-dontwarn io.rong.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# LocalCart's custom card messages are instantiated by the SDK through their no-arg constructors.
-keep class app.localcart.chat.CartCardMessage { *; }
-keep class app.localcart.chat.BillCardMessage { *; }
-keep class app.localcart.chat.StatusCardMessage { *; }

# Retrofit service interface: keep generic signatures for suspend functions.
-keep,allowobfuscation,allowshrinking interface app.localcart.data.api.LocalCartApi
# Response types of suspend functions appear only in Continuation generic signatures; when the app
# ignores a result (e.g. MessageIdResponse) R8 would otherwise remove the class and Retrofit would see
# java.lang.Object. Keep the API DTOs.
-keep class app.localcart.data.api.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# OkHttp optional platforms.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
