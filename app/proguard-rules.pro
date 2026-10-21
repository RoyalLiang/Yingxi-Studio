# JNI names and callbacks are part of the decoder's native ABI.
-keep class io.github.anilbeesetti.nextlib.** { *; }
-keep class androidx.media3.decoder.VideoDecoderOutputBuffer { *; }

# Crypto providers register algorithms by constructed class names.
# Preserve the installed provider's complete algorithm surface for SMB/SFTP.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# SMBJ's optional Kerberos and MBassador EL filters are not Android features.
# Username/password NTLM, guest access and the regular event handlers remain.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep @interface net.engio.mbassy.listener.Handler
-keepclassmembers class * { @net.engio.mbassy.listener.Handler <methods>; }

# MBassador reads the invocation class from @Handler and calls this exact
# constructor with reflection. Retain class identity and the constructor while
# allowing its name to be obfuscated; no application-wide keep rule is needed.
-keep,allowobfuscation class * implements net.engio.mbassy.dispatch.IHandlerInvocation {
    public <init>(net.engio.mbassy.subscription.SubscriptionContext);
}
