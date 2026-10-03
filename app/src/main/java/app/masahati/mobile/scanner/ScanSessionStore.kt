package app.masahati.mobile.scanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

enum class ScanFilter(val title: String) {
    ORIGINAL("الأصل"),AUTO("تلقائي"),CLEAN_WHITE("أبيض نظيف"),CLEAR_TEXT("نص واضح"),
    BLACK_WHITE("أبيض وأسود"),PHOTO("مستند ملون")
}
data class ScanPage(val id: String,val source: String,val sourceHash: String,
    var quad: DocumentQuad=DocumentQuad.inset(),var turns: Int=0,var filter: ScanFilter=ScanFilter.AUTO,
    var deleted: Boolean=false,var ready: Boolean=false,var review: Boolean=true,
    var dewarp: Boolean=true,var report: JSONObject=JSONObject(),var paperRatio: Double?=null,
    var hdrSource: String?=null,var hdrHash: String?=null)

/** Originals are immutable, durable files. A separate recipe survives process death.
 * Deleted/reordered pages keep their raw sources. All paths are internal and validated.
 */
class ScanSessionStore private constructor(private val context: Context,val id: String) {
    val directory=File(root(context),id).apply { mkdirs() }
    val pages=mutableListOf<ScanPage>()
    var documentName: String?=null
    val documentNames=linkedSetOf<String>()
    val visiblePages: List<ScanPage> get()=pages.filterNot { it.deleted }
    init { require(validId(id)) }
    fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,160}")) && name!="." && name!="..")
        return File(directory,name)
    }
    fun source(p: ScanPage)=file(p.source)
    fun processed(p: ScanPage)=file("${p.id}-processed.png")
    fun rectified(p: ScanPage)=file("${p.id}-rectified.png")
    fun hdr(p: ScanPage): File?=p.hdrSource?.let(::file)
    fun verifyHdr(p: ScanPage) { val source=hdr(p) ?: error("لا توجد صورة HDR")
        check(sha256(source)==p.hdrHash) { "تغيرت صورة HDR؛ بقيت الصورة الأصلية محفوظة" } }
    fun attachHdr(p: ScanPage,input: InputStream) {
        require(p in pages && p.hdrSource==null)
        val target=file("${p.id}-capture-hdr.jpg");require(!target.exists())
        val partial=file("${p.id}-capture-hdr.partial")
        try {
            partial.outputStream().buffered().use { output -> val buffer=ByteArray(65536);var total=0L
                while(true) { val n=input.read(buffer);if(n<0) break;total+=n
                    require(total<=128_000_000);output.write(buffer,0,n) } }
            ScanSourceImage.info(partial);val hash=sha256(partial);check(partial.renameTo(target))
            p.hdrSource=target.name;p.hdrHash=hash;save()
        } finally { partial.delete() }
    }
    fun import(uri: Uri): ScanPage = context.contentResolver.openInputStream(uri)?.use { import(it) } ?: error("تعذر فتح الصورة")
    fun import(input: InputStream): ScanPage {
        require(visiblePages.size<20) { "الحد الأقصى 20 صفحة" }
        val pageId=UUID.randomUUID().toString();val target=file("$pageId-original.jpg");val partial=file("$pageId.partial")
        try {
            partial.outputStream().buffered().use { output -> val buffer=ByteArray(65536);var total=0L
                while(true) { val n=input.read(buffer);if(n<0) break;total+=n
                    require(total<=128_000_000) { "الصورة أكبر من الحد الآمن" };output.write(buffer,0,n) } }
            ScanSourceImage.info(partial);check(partial.renameTo(target))
            val page=ScanPage(pageId,target.name,sha256(target));pages.add(page);save();return page
        } finally { partial.delete() }
    }
    fun verifySource(p: ScanPage) { check(sha256(source(p))==p.sourceHash) { "تغيرت الصورة الأصلية؛ أوقفنا المعالجة" } }
    fun saveBitmap(bitmap: Bitmap,target: File) {
        require(target.parentFile?.canonicalFile==directory.canonicalFile)
        val temporary=file("${target.name}.partial")
        bitmap.setHasAlpha(false)
        try { temporary.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            check(temporary.renameTo(target)) { "تعذر حفظ الصفحة" } } finally { temporary.delete() }
    }
    fun bindDocument(document: File) { documentName=document.name;documentNames.add(document.name);save() }
    @Synchronized fun save() {
        val value=JSONObject().put("format","masahati-scanner-v1").put("id",id).put("document_name",documentName)
            .put("document_names",JSONArray(documentNames.toList()))
            .put("updated_at",System.currentTimeMillis()).put("pages",JSONArray().apply {
                pages.forEach { p -> put(JSONObject().put("id",p.id).put("source",p.source).put("source_sha256",p.sourceHash)
                    .put("turns",p.turns).put("filter",p.filter.name).put("deleted",p.deleted).put("ready",p.ready)
                    .put("review",p.review).put("dewarp",p.dewarp).put("report",p.report).put("paper_ratio",p.paperRatio)
                    .put("hdr_source",p.hdrSource).put("hdr_sha256",p.hdrHash)
                    .put("quad",JSONArray().apply { p.quad.points.forEach { put(JSONArray().put(it.x).put(it.y)) } })
                    .put("confidence",p.quad.confidence).put("origin",p.quad.origin)) } })
        val atomic=AtomicFile(file("session.json"));val stream=atomic.startWrite()
        try { stream.write(value.toString(2).toByteArray(Charsets.UTF_8));atomic.finishWrite(stream) }
        catch(error: Throwable) { atomic.failWrite(stream);throw error }
    }
    companion object {
        fun root(context: Context)=File(context.filesDir,"scanner-sessions").apply { mkdirs() }
        fun validId(id: String)=id.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
        fun create(context: Context)=ScanSessionStore(context.applicationContext,UUID.randomUUID().toString()).apply { save() }
        fun open(context: Context,id: String): ScanSessionStore {
            require(validId(id));val store=ScanSessionStore(context.applicationContext,id)
            val manifest=store.file("session.json");require(manifest.length() in 1..2_000_000)
            val value=JSONObject(manifest.readText());require(value.optString("format")=="masahati-scanner-v1")
            require(value.getString("id")==id)
            store.documentName=value.optString("document_name").takeUnless { it=="null" || it.isBlank() }
            value.optJSONArray("document_names")?.let { names -> for(i in 0 until names.length()) store.documentNames.add(names.getString(i)) }
            store.documentName?.let { store.documentNames.add(it) }
            val entries=value.getJSONArray("pages");require(entries.length()<=100)
            for(i in 0 until entries.length()) {
                val p=entries.getJSONObject(i);val pageId=p.getString("id");require(validId(pageId))
                val source=p.getString("source");require(source=="$pageId-original.jpg")
                val q=p.getJSONArray("quad");require(q.length()==4)
                val quad=DocumentQuad((0..3).map { q.getJSONArray(it).let { a -> ScanPoint(a.getDouble(0),a.getDouble(1)) } },
                    p.optDouble("confidence",0.0),origin=p.optString("origin","manual"))
                val sourceHash=p.getString("source_sha256");require(sourceHash.matches(Regex("[0-9a-f]{64}")))
                val hdrSource=p.optString("hdr_source").takeUnless { it.isBlank() || it=="null" }
                val hdrHash=p.optString("hdr_sha256").takeUnless { it.isBlank() || it=="null" }
                require((hdrSource==null)==(hdrHash==null))
                require(hdrSource==null || (hdrSource=="$pageId-capture-hdr.jpg" && hdrHash!!.matches(Regex("[0-9a-f]{64}"))))
                store.pages.add(ScanPage(pageId,source,sourceHash,if(quad.valid()) quad else DocumentQuad.inset(),
                    p.optInt("turns").mod(4),runCatching { ScanFilter.valueOf(p.optString("filter")) }.getOrDefault(ScanFilter.AUTO),
                    p.optBoolean("deleted"),p.optBoolean("ready"),p.optBoolean("review",true),p.optBoolean("dewarp",true),
                    p.optJSONObject("report") ?: JSONObject(),p.optDouble("paper_ratio",Double.NaN).takeIf { it.isFinite() && it in .03..35.0 },hdrSource,hdrHash))
            }
            return store
        }
        fun forDocument(context: Context,document: File): ScanSessionStore? = root(context).listFiles().orEmpty().asSequence()
            .filter { it.isDirectory && validId(it.name) }.mapNotNull { runCatching { open(context,it.name) }.getOrNull() }
            .firstOrNull { document.name in it.documentNames }
        fun drafts(context: Context): List<ScanSessionStore> = root(context).listFiles().orEmpty()
            .filter { it.isDirectory && validId(it.name) }.mapNotNull { runCatching { open(context,it.name) }.getOrNull() }
            .filter { it.documentName==null && it.visiblePages.isNotEmpty() }.sortedByDescending { it.file("session.json").lastModified() }
        fun restore(context: Context,backup: File,document: File): ScanSessionStore {
            val manifest=File(backup,"session.json");require(manifest.length() in 1..2_000_000)
            val value=JSONObject(manifest.readText());require(value.getString("format")=="masahati-scanner-v1")
            val store=create(context)
            try {
                value.put("id",store.id).put("document_name",document.name).put("document_names",JSONArray().put(document.name))
                store.file("session.json").writeText(value.toString())
                val validated=open(context,store.id)
                for(page in validated.pages) {
                    val source=File(backup,page.source)
                    require(source.isFile && source.length()<=128_000_000 && sha256(source)==page.sourceHash)
                    ScanSourceImage.info(source);source.copyTo(validated.source(page),false)
                    validated.hdr(page)?.let { target ->
                        val original=File(backup,target.name)
                        require(original.isFile && original.length()<=128_000_000 && sha256(original)==page.hdrHash)
                        ScanSourceImage.info(original);original.copyTo(target,false)
                    }
                    listOf(validated.rectified(page),validated.processed(page)).forEach { target ->
                        val old=File(backup,target.name)
                        if(old.isFile) { require(old.length()<=128_000_000);ScanSourceImage.info(old);old.copyTo(target,false) }
                    }
                    page.ready=page.ready && validated.processed(page).isFile
                }
                validated.save();return validated
            } catch(error: Throwable) { store.directory.deleteRecursively();throw error }
        }
        fun sha256(file: File): String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input -> val buffer=ByteArray(65536)
                while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
