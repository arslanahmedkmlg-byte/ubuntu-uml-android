package com.eaglenet.ubuntuuml

/** Live guest tmux windows, reported through its OSC 0 terminal title. */
internal data class GuestTab(val index: Int, val name: String, val active: Boolean)

internal fun parseGuestTabs(title: String): List<GuestTab> {
    if (!title.startsWith("UMLTABS|")) return emptyList()
    return title.removePrefix("UMLTABS|").split(';').mapNotNull { record ->
        val fields = record.split('~', limit = 3)
        if (fields.size != 3) return@mapNotNull null
        val index = fields[0].toIntOrNull()?.takeIf { it in 0..999 } ?: return@mapNotNull null
        val name = fields[1].trim().take(32).ifBlank { "Shell $index" }
        GuestTab(index, name, fields[2] == "1")
    }.distinctBy { it.index }.take(20)
}

internal fun selectGuestTabInput(index: Int): ByteArray {
    require(index in 0..999)
    return "\u0002:select-window -t :$index\r".toByteArray(Charsets.UTF_8)
}
