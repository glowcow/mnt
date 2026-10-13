# kotlinx.serialization: keep generated serializers of @Serializable classes (nav keys).
-keepclassmembers @kotlinx.serialization.Serializable class dev.glowcow.mnt.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# smbj: Kerberos sign-in and the event bus's expression filters lean on classes Android does not have; neither is used.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
