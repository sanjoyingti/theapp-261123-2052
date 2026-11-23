package com.arin.bypass.engine

/** Small unified-diff writer used for every preview and every ledger entry. */
object Diff {

    private class Op(val tag: Char, val text: String, val oldNo: Int, val newNo: Int)

    fun unified(path: String, oldText: String, newText: String, context: Int = 3): String {
        if (oldText == newText) return ""
        val old = oldText.split("\n")
        val new = newText.split("\n")
        val ops = if (old.size.toLong() * new.size.toLong() <= 2_000_000L) {
            lcs(old, new)
        } else {
            wholeFile(old, new)
        }

        val out = StringBuilder()
        out.append("--- a/").append(path).append('\n')
        out.append("+++ b/").append(path).append('\n')

        var i = 0
        while (i < ops.size) {
            if (ops[i].tag == ' ') {
                i++
                continue
            }
            var start = i
            var lead = 0
            while (start > 0 && ops[start - 1].tag == ' ' && lead < context) {
                start--
                lead++
            }
            var end = i
            while (end < ops.size && ops[end].tag != ' ') end++
            var after = end
            var trail = 0
            while (after < ops.size && ops[after].tag == ' ' && trail < context) {
                after++
                trail++
            }

            var removed = 0
            var added = 0
            for (k in start until after) {
                if (ops[k].tag == '-') removed++
                if (ops[k].tag == '+') added++
            }

            out.append("@@ -").append(ops[start].oldNo).append(',').append(removed)
                .append(" +").append(ops[start].newNo).append(',').append(added).append(" @@")
                .append('\n')
            for (k in start until after) {
                out.append(ops[k].tag).append(ops[k].text).append('\n')
            }
            i = after
        }
        return out.toString()
    }

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
                    out.add(Op(' ', a[i], i + 1, j + 1))
                    i++
                    j++
                }
                table[i + 1][j] >= table[i][j + 1] -> {
                    out.add(Op('-', a[i], i + 1, j + 1))
                    i++
                }
                else -> {
                    out.add(Op('+', b[j], i + 1, j + 1))
                    j++
                }
            }
        }
        while (i < n) {
            out.add(Op('-', a[i], i + 1, j + 1))
            i++
        }
        while (j < m) {
            out.add(Op('+', b[j], i + 1, j + 1))
            j++
        }
        return out
    }

    private fun wholeFile(a: List<String>, b: List<String>): List<Op> {
        val out = ArrayList<Op>(a.size + b.size)
        for (i in a.indices) out.add(Op('-', a[i], i + 1, 1))
        for (j in b.indices) out.add(Op('+', b[j], a.size + 1, j + 1))
        return out
    }
}
