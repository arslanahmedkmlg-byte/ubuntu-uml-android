package com.eaglenet.ubuntuuml

import android.graphics.Typeface
import android.content.res.AssetManager

/** The nine explicitly supplied typefaces; no implicit system/custom options. */
internal val terminalFontFiles = listOf(
    "JetBrainsMono-Bold.ttf", "JetBrainsMono-Medium.ttf", "JetBrainsMono-Regular.ttf",
    "JetBrainsMonoNL-SemiBold.ttf", "JetBrainsMonoNL-ThinItalic.ttf",
    "Hack-Bold.ttf", "Hack-BoldItalic.ttf", "Hack-Italic.ttf", "Hack-Regular.ttf",
)

internal fun loadTerminalTypeface(assets: AssetManager, file: String): Typeface {
    require(file in terminalFontFiles) { "Unknown font" }
    return Typeface.createFromAsset(assets, "fonts/$file")
}
