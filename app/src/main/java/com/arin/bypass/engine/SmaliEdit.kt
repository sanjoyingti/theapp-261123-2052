package com.arin.bypass.engine

/** Text level smali surgery: find the method behind a symbol and make it answer early. */
object SmaliEdit {

    enum class Answer { TRUE, FALSE, NULL }

    fun forceReturn(text: String, symbol: String, answer: Answer): String? {
        val name = symbol.substringAfter("->", "").substringBefore("(")
        if (name.isEmpty()) return null
        val returnType = symbol.substringAfterLast(")", "")
        val lines = text.split("\n").toMutableList()

        val declareAt = lines.indexOfFirst { it.trimStart().startsWith(".method") && it.contains("->$name(") }
        if (declareAt < 0) return null
        val endAt = (declareAt + 1 until lines.size).firstOrNull { lines[it].trim() == ".end method" } ?: return null

        val isStatic = lines[declareAt].contains(" static ")
        val params = registerParams(symbol)
        val needed = params + (if (isStatic) 0 else 1) + 1

        var slot = -1
        for (i in declareAt + 1 until endAt) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith(".locals")) {
                val current = trimmed.removePrefix(".locals").trim().toIntOrNull() ?: 0
                val want = maxOf(current, needed - (if (isStatic) 0 else 0))
                if (want != current) lines[i] = "    .locals $want"
                slot = i
                break
            }
            if (trimmed.startsWith(".registers")) {
                val current = trimmed.removePrefix(".registers").trim().toIntOrNull() ?: 0
                if (current < needed + (if (isStatic) 0 else params + 1)) {
                    lines[i] = "    .registers ${needed + (if (isStatic) 0 else params + 1)}"
                }
                slot = i
                break
            }
            if (trimmed.startsWith(".annotation") || trimmed.startsWith(".param")) continue
        }
        if (slot < 0) return null

        val body = when (answer) {
            Answer.TRUE -> if (returnType == "Z" || returnType == "I") listOf("    const/4 v0, 0x1", "    return v0") else null
            Answer.FALSE -> if (returnType == "Z" || returnType == "I") listOf("    const/4 v0, 0x0", "    return v0") else null
            Answer.NULL -> if (returnType.startsWith("L") || returnType.startsWith("[")) {
                listOf("    const/4 v0, 0x0", "    return-object v0")
            } else null
        } ?: return null

        if (lines.getOrNull(slot + 1)?.trim() == body.first().trim()) return null
        lines.addAll(slot + 1, body)
        return lines.joinToString("\n")
    }

    fun replaceString(text: String, old: String, replacement: String): String? {
        if (!text.contains(old)) return null
        val changed = text.replace(old, replacement)
        return if (changed == text) null else changed
    }

    fun methodDescriptor(symbol: String): String = symbol.substringAfter("->", "").substringBefore("(")

    private fun registerParams(symbol: String): Int {
        val proto = symbol.substringAfter("(", "").substringBefore(")")
        var count = 0
        var i = 0
        while (i < proto.length) {
            when (proto[i]) {
                'J', 'D' -> { count += 2; i++ }
                '[' -> { count += 1; while (i < proto.length && proto[i] == '[') i++; if (i < proto.length) i++ }
                'L' -> { count += 1; i = proto.indexOf(';', i).let { if (it < 0) proto.length else it + 1 } }
                else -> { count += 1; i++ }
            }
        }
        return count
    }
}
