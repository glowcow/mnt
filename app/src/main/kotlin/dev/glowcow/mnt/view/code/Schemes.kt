package dev.glowcow.mnt.view.code

/**
 * The colours code is shown in. [dark] says which colours of the app go round it, null for the theme's own;
 * a null [ground], [text] or [gutter] is the app's ground, text or muted colour; no tones is plain text.
 */
enum class CodeScheme(val dark: Boolean?, val ground: Long?, val text: Long?, val gutter: Long?, val tones: Map<Tone, Long>) {
    LIGHT(
        false, null, null, null,
        tones(
            keyword = 0xFFCF222E, type = 0xFF953800, string = 0xFF0A3069, number = 0xFF0550AE, comment = 0xFF6E7781, literal = 0xFF0550AE,
            meta = 0xFF8250DF, attr = 0xFF0550AE, tag = 0xFF116329, variable = 0xFF953800, func = 0xFF8250DF, section = 0xFF0550AE,
            added = 0xFF116329, removed = 0xFF82071E, error = 0xFFCF222E, warning = 0xFF9A6700, info = 0xFF0550AE,
        ),
    ),
    DARK(
        true, null, null, null,
        tones(
            keyword = 0xFFFF7B72, type = 0xFFFFA657, string = 0xFFA5D6FF, number = 0xFF79C0FF, comment = 0xFF8B949E, literal = 0xFF79C0FF,
            meta = 0xFFD2A8FF, attr = 0xFF79C0FF, tag = 0xFF7EE787, variable = 0xFFFFA657, func = 0xFFD2A8FF, section = 0xFF79C0FF,
            added = 0xFF7EE787, removed = 0xFFFFA198, error = 0xFFFF7B72, warning = 0xFFD29922, info = 0xFF79C0FF,
        ),
    ),
    SOLARIZED_LIGHT(false, 0xFFFDF6E3, 0xFF657B83, 0xFF93A1A1, solarized()),
    SOLARIZED_DARK(true, 0xFF002B36, 0xFF839496, 0xFF586E75, solarized()),
    MONOKAI(
        true, 0xFF272822, 0xFFF8F8F2, 0xFF90908A,
        tones(
            keyword = 0xFFF92672, type = 0xFF66D9EF, string = 0xFFE6DB74, number = 0xFFAE81FF, comment = 0xFF75715E, literal = 0xFFAE81FF,
            meta = 0xFF66D9EF, attr = 0xFFA6E22E, tag = 0xFFF92672, variable = 0xFFFD971F, func = 0xFFA6E22E, section = 0xFFE6DB74,
            added = 0xFFA6E22E, removed = 0xFFF92672, error = 0xFFF92672, warning = 0xFFE6DB74, info = 0xFF66D9EF,
        ),
    ),
    DRACULA(
        true, 0xFF282A36, 0xFFF8F8F2, 0xFF6272A4,
        tones(
            keyword = 0xFFFF79C6, type = 0xFF8BE9FD, string = 0xFFF1FA8C, number = 0xFFBD93F9, comment = 0xFF6272A4, literal = 0xFFBD93F9,
            meta = 0xFFFFB86C, attr = 0xFF50FA7B, tag = 0xFFFF79C6, variable = 0xFFFFB86C, func = 0xFF50FA7B, section = 0xFFBD93F9,
            added = 0xFF50FA7B, removed = 0xFFFF5555, error = 0xFFFF5555, warning = 0xFFF1FA8C, info = 0xFF8BE9FD,
        ),
    ),
    NONE(null, null, null, null, emptyMap());

    companion object {
        /** What the viewer shows code in until another is chosen: the light and the dark one of the app. */
        val defaultLight = LIGHT
        val defaultDark = DARK
    }
}

private fun tones(
    keyword: Long, type: Long, string: Long, number: Long, comment: Long, literal: Long, meta: Long, attr: Long, tag: Long,
    variable: Long, func: Long, section: Long, added: Long, removed: Long, error: Long, warning: Long, info: Long,
) = mapOf(
    Tone.KEYWORD to keyword, Tone.TYPE to type, Tone.STRING to string, Tone.NUMBER to number, Tone.COMMENT to comment, Tone.LITERAL to literal,
    Tone.META to meta, Tone.ATTR to attr, Tone.TAG to tag, Tone.VARIABLE to variable, Tone.FUNC to func, Tone.SECTION to section,
    Tone.ADDED to added, Tone.REMOVED to removed, Tone.ERROR to error, Tone.WARNING to warning, Tone.INFO to info,
)

// Solarized's accents read on both of its grounds.
private fun solarized() = tones(
    keyword = 0xFF859900, type = 0xFFB58900, string = 0xFF2AA198, number = 0xFFD33682, comment = 0xFF93A1A1, literal = 0xFF6C71C4,
    meta = 0xFFCB4B16, attr = 0xFF268BD2, tag = 0xFF268BD2, variable = 0xFFCB4B16, func = 0xFF268BD2, section = 0xFF268BD2,
    added = 0xFF859900, removed = 0xFFDC322F, error = 0xFFDC322F, warning = 0xFFB58900, info = 0xFF268BD2,
)
