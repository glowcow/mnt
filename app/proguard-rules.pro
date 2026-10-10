# kotlinx.serialization: keep generated serializers of @Serializable classes (nav keys).
-keepclassmembers @kotlinx.serialization.Serializable class dev.glowcow.mnt.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
