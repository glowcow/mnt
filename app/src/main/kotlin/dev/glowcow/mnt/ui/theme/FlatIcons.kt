package dev.glowcow.mnt.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Flat Color Icons (Icons8, MIT): what a file is and where files are kept, in their own colours softened
 * towards pastel. Never tinted. Each is the shapes of the icon's SVG on its 48×48 viewport.
 */
object MntFlat {
    /** A shape of an icon: its outline, and the colour that fills it or, for a line, draws it [width] thick. */
    private class Part(val data: String, val fill: Long? = null, val stroke: Long? = null, val width: Float = 0f)

    private fun icon(name: String, vararg parts: Part) =
        ImageVector.Builder(name, 24.dp, 24.dp, 48f, 48f).apply {
            parts.forEach {
                addPath(
                    PathParser().parsePathString(it.data).toNodes(),
                    fill = it.fill?.let { colour -> SolidColor(Color(colour)) },
                    stroke = it.stroke?.let { colour -> SolidColor(Color(colour)) },
                    strokeLineWidth = it.width,
                    strokeLineCap = StrokeCap.Round,
                )
            }
        }.build()

    // What a row of a folder is. The set draws every file as one blue sheet; here a video is red, a sound violet, a plain file grey.
    val Folder = icon(
        "folder",
        Part("M40 12H22l-4-4H8c-2.2 0-4 1.8-4 4v8h40v-4c0-2.2-1.8-4-4-4", fill = 0xFFF3B959),
        Part("M40 12H8c-2.2 0-4 1.8-4 4v20c0 2.2 1.8 4 4 4h32c2.2 0 4-1.8 4-4V16c0-2.2-1.8-4-4-4", fill = 0xFFF4D573),
    )
    val File = icon(
        "file",
        Part("M40 45H8V3h22l10 10z", fill = 0xFFC9D1D5),
        Part("M38.5 14H29V4.5z", fill = 0xFFF2F4F5),
    )
    val Text = icon(
        "document",
        Part("M40 45H8V3h22l10 10z", fill = 0xFFB6D9F6),
        Part("M38.5 14H29V4.5z", fill = 0xFFEBF7FD),
        Part("M16 21h17v2H16zm0 4h13v2H16zm0 4h17v2H16zm0 4h13v2H16z", fill = 0xFF5E9FE0),
    )
    val Image = icon(
        "picture",
        Part("M40 41H8c-2.2 0-4-1.8-4-4V11c0-2.2 1.8-4 4-4h32c2.2 0 4 1.8 4 4v26c0 2.2-1.8 4-4 4", fill = 0xFFF2A352),
        Part("M32,16a3,3 0 1,0 6,0a3,3 0 1,0 -6,0Z", fill = 0xFFFCF8D9),
        Part("M20 16L9 32h22z", fill = 0xFFBC4019),
        Part("m31 22l-8 10h16z", fill = 0xFFDE4B1E),
    )
    val Video = icon(
        "video-file",
        Part("M40 45H8V3h22l10 10z", fill = 0xFFF0BCBC),
        Part("M38.5 14H29V4.5z", fill = 0xFFFEF2F4),
        Part("m30 28l-10-6v12z", fill = 0xFFD87575),
    )
    val Audio = icon(
        "audio-file",
        Part("M204 0h48v48h-48z"),
        Part("M244 45h-32V3h22l10 10z", fill = 0xFFDAB7E0),
        Part("M242.5 14H233V4.5z", fill = 0xFFF6EEF7),
        Part("M223,30a4,4 0 1,0 8,0a4,4 0 1,0 -8,0Z", fill = 0xFF9733C1),
        Part("m234 21l-5-2v11h2v-7.1l3 1.1z", fill = 0xFF9733C1),
        Part("M40 45H8V3h22l10 10z", fill = 0xFFDAB7E0),
        Part("M38.5 14H29V4.5z", fill = 0xFFF6EEF7),
        Part("M19,30a4,4 0 1,0 8,0a4,4 0 1,0 -8,0Z", fill = 0xFF9733C1),
        Part("m30 21l-5-2v11h2v-7.1l3 1.1z", fill = 0xFF9733C1),
    )
    val Archive = icon(
        "package",
        Part("M38 42H10c-2.2 0-4-1.8-4-4V10c0-2.2 1.8-4 4-4h28c2.2 0 4 1.8 4 4v28c0 2.2-1.8 4-4 4", fill = 0xFFF3B459),
        Part("M29.5 16h-11c-.8 0-1.5-.7-1.5-1.5s.7-1.5 1.5-1.5h11c.8 0 1.5.7 1.5 1.5s-.7 1.5-1.5 1.5", fill = 0xFFB8720E),
    )
    val Package = icon(
        "android-os",
        Part("M12 29.001a2 2 0 0 1-4 0v-9a2 2 0 0 1 4 0zm28 0a2 2 0 0 1-4 0v-9a2 2 0 0 1 4 0zM22 40a2 2 0 0 1-4 0v-9a2 2 0 0 1 4 0zm8 0a2 2 0 0 1-4 0v-9a2 2 0 0 1 4 0z", fill = 0xFFA3C77D),
        Part("M14 18.001V33a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V18.001zM24 8c-6 0-9.655 3.645-10 8h20c-.346-4.355-4-8-10-8m-4 5.598a1 1 0 1 1 0-2a1 1 0 0 1 0 2m8 0a1 1 0 1 1 0-2a1 1 0 0 1 0 2", fill = 0xFFA3C77D),
        Part("m30 7l-1.666 2.499M18 7l1.333 2.082", stroke = 0xFFA3C77D, width = 2f),
    )

    // Where files are kept.
    val Phone = icon(
        "phone-android",
        Part("M12 40V8c0-2.2 1.8-4 4-4h16c2.2 0 4 1.8 4 4v32c0 2.2-1.8 4-4 4H16c-2.2 0-4-1.8-4-4", fill = 0xFF526670),
        Part("M32 7H16c-.6 0-1 .4-1 1v29c0 .6.4 1 1 1h16c.6 0 1-.4 1-1V8c0-.6-.4-1-1-1", fill = 0xFFD3E8F9),
        Part("M21 40h6v2h-6z", fill = 0xFFA2B1B8),
    )
    val Card = icon(
        "sim-card",
        Part("M36 45H12c-2.2 0-4-1.8-4-4V7c0-2.2 1.8-4 4-4h16.3c1.1 0 2.1.4 2.8 1.2l7.7 7.7c.8.8 1.2 1.8 1.2 2.8V41c0 2.2-1.8 4-4 4", fill = 0xFF0FC1B0),
        Part("M32 38H16c-1.1 0-2-.9-2-2V24c0-1.1.9-2 2-2h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2", fill = 0xFFF3B459),
        Part("M29 30v3h5v2h-5v3h-2V22h2v6h5v2zm-15-1v2h5v2h-5v2h5v3h2v-9z", fill = 0xFFF6DD8C),
    )
    val Drive = icon(
        "usb",
        Part("M38.701 24.355h-2.189l-.467 2.265h2.51c.233 0 1.545-.167 1.545-1.267c0-1.087-1.399-.998-1.399-.998m.82-4.016h-2.15l-.374 1.796h2.337c.188 0 1.113-.146 1.113-1.006c0-.858-.926-.79-.926-.79m4.543 2.77s1.436-.743 1.436-3.093c0-3.715-4.377-3.516-4.377-3.516h-2.865l.674-3H17.961c-9.344 0-12.158 6.774-12.158 6.774l-.067.226H2.547l-1.047 6h3.37l.001.143S4.586 33.5 15.334 33.5h19.042l.679-3h4.389c4.729 0 5.591-3.354 5.591-4.9c-.003-1.762-.971-2.491-.971-2.491", fill = 0xFF2C7DD8),
        Part("M38.701 24.355h-2.189l-.467 2.265h2.51c.233 0 1.545-.167 1.545-1.267c0-1.087-1.399-.998-1.399-.998m.82-4.016h-2.15l-.374 1.796h2.337c.188 0 1.113-.146 1.113-1.006c0-.858-.926-.79-.926-.79M14.022 29.5c-5.306 0-5.306-3.624-5.238-3.986a1911 1911 0 0 1 1.789-8.014h3.84l-1.358 6.354s-.971 2.728 1.251 2.728c2.081 0 2.336-2.535 2.336-2.535l1.465-6.543h3.839l-1.582 6.979c.001-.003-.106 5.017-6.342 5.017m12.076.021c-2.674 0-4.958-1.262-4.856-4.14h3.438c0 .576.086 1.627 1.633 1.627c.627 0 1.688-.266 1.688-1.133c0-1.631-5.597-.785-5.597-4.57c0-2.063 1.899-3.785 4.989-3.785c4.976 0 4.613 3.749 4.613 3.749h-3.369c0-1.044-.664-1.204-1.463-1.204c-.8 0-1.372.343-1.372.944c0 1.471 5.634.456 5.634 4.531c0 1.765-1.424 3.981-5.338 3.981m13.268-.021h-7.515l2.601-12h6.556c1.113 0 3.43.234 3.43 2.542c0 2.602-2.227 3.013-2.227 3.013s1.764.407 1.764 2.473c0 3.929-4.2 3.972-4.609 3.972", fill = 0xFFFFFFFF),
    )
    val Download = icon(
        "download",
        Part("M24 37.1L13 24h22zM20 4h8v4h-8zm0 6h8v4h-8z", fill = 0xFF2C7DD8),
        Part("M20 16h8v11h-8zM6 40h36v4H6z", fill = 0xFF2C7DD8),
    )
    val Camera = icon(
        "camera",
        Part("M33.9 12.1H14.2L17.6 7c.4-.6 1-.9 1.7-.9h9.6c.7 0 1.3.3 1.7.9z", fill = 0xFF6944C0),
        Part("M14 11H8V9.2C8 8.5 8.5 8 9.2 8h3.6c.7 0 1.2.5 1.2 1.2z", fill = 0xFFAC99D1),
        Part("M40 42H8c-2.2 0-4-1.8-4-4V14c0-2.2 1.8-4 4-4h32c2.2 0 4 1.8 4 4v24c0 2.2-1.8 4-4 4", fill = 0xFF8E70CA),
        Part("M12,26a12,12 0 1,0 24,0a12,12 0 1,0 -24,0Z", fill = 0xFF6944C0),
        Part("M15,26a9,9 0 1,0 18,0a9,9 0 1,0 -18,0Z", fill = 0xFFCBB2F9),
        Part("M29 23c-1.2-1.4-3-2.2-4.8-2.2s-3.6.8-4.8 2.2c-.5.5-.4 1.3.1 1.8s1.3.4 1.8-.1c1.5-1.7 4.3-1.7 5.8 0c.3.3.6.4 1 .4q.45 0 .9-.3c.4-.4.5-1.3 0-1.8", fill = 0xFFD9C6FB),
        Part("M9,13.5a2,1.5 0 1,0 4,0a2,1.5 0 1,0 -4,0Z", fill = 0xFFAC99D1),
    )
    val Music = icon(
        "music",
        Part("M10,33a9,9 0 1,0 18,0a9,9 0 1,0 -18,0Z", fill = 0xFFE66B95),
        Part("M24 6v27h4V14l11 3v-7z", fill = 0xFFE66B95),
    )
    val Movies = icon(
        "film",
        Part("M45 9H3v30h42zM22 37v-4h4v4zm8 0v-4h4v4zm8 0v-4h4v4zm-24 0v-4h4v4zm-8 0v-4h4v4zm16-22v-4h4v4zm8 0v-4h4v4zm8 0v-4h4v4zm-24 0v-4h4v4zm-8 0v-4h4v4z", fill = 0xFF7B87C9),
    )
}
