package com.arin.bypass.engine

/** Small unified-diff writer used for every preview and every ledger entry. */
object Diff {

    fun unified(path: String, oldText: String, newText: String, context: Int = 3): String {
        if (oldText == newText) return ""
        val old = oldText.split("\n")
        val new = newText.split("\n")
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
        ) suffix++

        val oldMid = old.subList(prefix, old.size - suffix)
        val newMid = new.subList(prefix, new.size - suffix)
        val ops = if (oldMid.size * newMid.size <= 1_000_000) lcs(oldMid, newMid)
        else listOf(Op(' ', -1, -1)).let { _ ->
            oldMid.map { Op('-', it, -1) } + newMid.map { Op('+', -1, it) }
        }

        val builder = StringBuilder()
        builder.append("--- a/").append(path).append('\n')
        builder.append("+++ b/").append(path).append('\n')
        var oldLine = 1
        var newLine = 1
        var i = 0
        while (i < ops.size) {
            if (ops[i].tag == ' ') {
                oldLine++
                newLine++
                i++
                continue
            }
            var end = i
            while (end < ops.size && ops[end].tag != ' ') end++
            val hunkOldStart = oldLine
            val hunkNewStart = newLine
            var removed = 0
            var added = 0
            val body = StringBuilder()
            for (k in i until end) {
                val op = ops[k]
                if (op.tag == '-') {
                    body.append('-').append(op.oldLine).append('\n')
                    removed++
                    oldLine++
                } else {
                    body.append('+').append(op.newLine).append('\n')
                    added++
                    newLine++
                }
            }
            builder.append("@@ -").append(hunkOldStart).append(',').append(removed)
                .append(" +").append(hunkNewStart).append(',').append(added).append(" @@").append('\n')
            builder.append(body)
            i = end
        }
        return builder.toString()
    }

    private class Op(val tag: Char, val oldLine: Int, val newLine: Int)

    private fun lcs(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1
                else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        val out = ArrayList<Op>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(Op(' ', i + 1, j + 1))
                    i++
                    j++
                }
                table[i + 1][j] >= table[i][j + 1] -> {
                    out.add(Op('-', i + 1, -1))
                    i++
                }
                else -> {
                    out.add(Op('+', -1, j + 1))
                    j++
                }
            }
        }
        while (i < n) {
            out.add(Op('-', i + 1, -1))
            i++
        }
        while (j < m) {
            out.add(Op('+', -1, j + 1))
            j++
        }
        return out
    }
}
