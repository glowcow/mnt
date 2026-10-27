# kotlinx.serialization: keep generated serializers of @Serializable classes (nav keys).
-keepclassmembers @kotlinx.serialization.Serializable class dev.glowcow.mnt.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# smbj: Kerberos sign-in and the event bus's expression filters lean on classes Android does not have; neither is used.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**

# The archive reader's native code calls these by name.
-keep class dev.glowcow.mnt.archive.ArchiveSource { int read(byte[]); long seek(long, int); }
-keep class dev.glowcow.mnt.archive.ArchiveException { <init>(int, byte[]); }

# LibVLC's native code calls back into its Java side by name, and its AAR brings no rules of its own.
-keep class org.videolan.libvlc.** { *; }
