package com.arin.bypass.engine

/** Renders a binary manifest as plain text so a change can be previewed as a diff. */
fun Axml.summary(bytes: ByteArray): String = runCatching {
    val builder = StringBuilder()
    val root = Axml.parse(bytes).manifest()
    render(root, 0, builder)
    builder.toString()
}.getOrDefault("(binary settings file)")

private fun render(element: Axml.Element, depth: Int, builder: StringBuilder) {
    val pad = "  ".repeat(depth)
    builder.append(pad).append('<').append(element.name)
    for (attr in element.attrs) {
        builder.append(' ').append(attr.name).append("=\"")
        builder.append(attr.raw ?: attr.data.toString())
        builder.append('"')
    }
    builder.append('>').append('\n')
    for (child in element.children) render(child, depth + 1, builder)
}
