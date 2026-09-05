package app.masahati.mobile

internal object NearDuplicateFingerprint {
    fun fingerprint(text: String): String? {
        val normalized = SmartSearch.normalize(text)
        val tokens = normalized.split(' ').filter { it.length >= 2 }
        if (tokens.size < 6 || normalized.length < 80) return null

        val features = mutableListOf<String>()
        features += tokens
        for (i in 0 until tokens.lastIndex) {
            features += tokens[i] + "_" + tokens[i + 1]
        }

        val weights = IntArray(64)
        features.forEach { feature ->
            val hash = fnv1a64(feature)
            for (bit in 0 until 64) {
                if (((hash ushr bit) and 1L) == 1L) weights[bit] += 1
                else weights[bit] -= 1
            }
        }

        var value = 0L
        for (bit in 0 until 64) {
            if (weights[bit] >= 0) value = value or (1L shl bit)
        }
        return java.lang.Long.toUnsignedString(value, 16).padStart(16, '0')
    }

    fun distance(a: String, b: String): Int? {
        val x = runCatching { java.lang.Long.parseUnsignedLong(a, 16) }.getOrNull() ?: return null
        val y = runCatching { java.lang.Long.parseUnsignedLong(b, 16) }.getOrNull() ?: return null
        return java.lang.Long.bitCount(x xor y)
    }

    private fun fnv1a64(value: String): Long {
        var hash = -3750763034362895579L
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 1099511628211L
        }
        return hash
    }
}
