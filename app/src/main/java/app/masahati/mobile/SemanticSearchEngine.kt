package app.masahati.mobile

import android.content.Context
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class SemanticSearchHit(
    val message: MessageRow,
    val score: Double,
    val excerpt: String
)

class SemanticSearchEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val packs = SemanticModelPackManager(appContext)
    private var embedder: TextEmbedder? = null

    fun isReady(): Boolean = packs.isInstalled()

    fun indexMissing(db: MasahatiDatabase, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        if (!isReady()) return 0
        val chunks = db.listDocumentChunks(onlyWithoutEmbedding = true)
        if (chunks.isEmpty()) return 0
        val runtime = runtime()
        var completed = 0
        chunks.forEach { chunk ->
            val message = db.getMessage(chunk.messageId)
            if (message != null) {
                val meta = db.getDocumentMeta(message.id)
                val title = meta?.smartTitle ?: message.displayName ?: "مستند"
                val vector = embed(runtime, "title: ${title.take(180)} | text: ${chunk.text.take(1800)}")
                if (vector != null) {
                    db.setChunkEmbedding(chunk.messageId, chunk.chunkIndex, encode(vector))
                    completed++
                }
            }
            onProgress(completed, chunks.size)
        }
        return completed
    }

    fun search(db: MasahatiDatabase, query: String, limit: Int = 20): List<SemanticSearchHit> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()

        val lexical = db.search(clean, 30)
        if (!isReady()) {
            return lexical.take(limit).mapIndexed { index, row ->
                SemanticSearchHit(row, 1.0 - index * 0.02, bestExcerpt(row, clean))
            }
        }

        indexMissing(db)
        val queryVector = embed(runtime(), "task: search result | query: ${clean.take(1000)}")
            ?: return lexical.take(limit).mapIndexed { index, row ->
                SemanticSearchHit(row, 1.0 - index * 0.02, bestExcerpt(row, clean))
            }

        val semanticByMessage = mutableMapOf<Long, Pair<Double, String>>()
        db.listDocumentChunks().forEach { chunk ->
            val stored = chunk.embedding ?: return@forEach
            val vector = decode(stored)
            if (vector.size != queryVector.size) return@forEach
            val score = cosine(queryVector, vector)
            val old = semanticByMessage[chunk.messageId]
            if (old == null || score > old.first) {
                semanticByMessage[chunk.messageId] = score to chunk.text.take(700)
            }
        }

        val lexicalBonus = lexical.mapIndexed { index, row ->
            row.id to (0.20 * (1.0 - index.coerceAtMost(20) / 25.0))
        }.toMap()

        val ids = LinkedHashSet<Long>()
        ids.addAll(semanticByMessage.keys)
        ids.addAll(lexical.map { it.id })

        return ids.mapNotNull { id ->
            val message = db.getMessage(id) ?: return@mapNotNull null
            val semantic = semanticByMessage[id]?.first ?: 0.0
            val bonus = lexicalBonus[id] ?: 0.0
            val finalScore = semantic * 0.80 + bonus
            val excerpt = semanticByMessage[id]?.second ?: bestExcerpt(message, clean)
            SemanticSearchHit(message, finalScore, excerpt)
        }
            .filter { it.score >= 0.28 || it.message in lexical.take(8) }
            .sortedWith(compareByDescending<SemanticSearchHit> { it.score }.thenByDescending { it.message.createdAt })
            .take(limit.coerceIn(1, 50))
    }

    private fun runtime(): TextEmbedder =
        embedder ?: TextEmbedder.createFromFile(appContext, packs.modelFile()).also { embedder = it }

    private fun embed(runtime: TextEmbedder, input: String): FloatArray? {
        val embedding = runtime.embed(input).embeddingResult().embeddings().firstOrNull() ?: return null
        val original = embedding.floatEmbedding()
        if (original.isEmpty()) return null
        val size = minOf(256, original.size)
        val vector = original.copyOf(size)
        normalize(vector)
        return vector
    }

    private fun normalize(vector: FloatArray) {
        var sum = 0.0
        vector.forEach { sum += it.toDouble() * it.toDouble() }
        val norm = sqrt(sum).takeIf { it > 0.0 } ?: return
        for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
        return dot
    }

    private fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(4 + vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(vector.size)
        vector.forEach(buffer::putFloat)
        return buffer.array()
    }

    private fun decode(bytes: ByteArray): FloatArray {
        if (bytes.size < 8) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val size = buffer.int
        if (size <= 0 || bytes.size != 4 + size * 4) return FloatArray(0)
        return FloatArray(size) { buffer.float }
    }

    private fun bestExcerpt(row: MessageRow, query: String): String {
        val source = row.summary?.takeIf { it.isNotBlank() }
            ?: row.ocrText?.takeIf { it.isNotBlank() }
            ?: row.text
        if (source.isBlank()) return row.displayName.orEmpty()
        val normalizedQuery = SmartSearch.normalize(query)
        val token = normalizedQuery.split(' ').firstOrNull { it.length >= 3 }
        val normalizedSource = SmartSearch.normalize(source)
        val pos = token?.let(normalizedSource::indexOf)?.takeIf { it >= 0 } ?: 0
        val start = (pos - 180).coerceAtLeast(0)
        return source.substring(start, (start + 700).coerceAtMost(source.length)).trim()
    }

    override fun close() {
        embedder?.close()
        embedder = null
    }
}
