package dev.foldman.playback

/** Orders "2 …" before "10 …", as people number their files. */
internal object NaturalOrder : Comparator<String> {
    private val chunks = Regex("""\d+|\D+""")
    override fun compare(a: String, b: String): Int {
        val x = chunks.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunks.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val cmp = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                    ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else p.compareTo(q)
            if (cmp != 0) return cmp
        }
        return x.size.compareTo(y.size)
    }
}
