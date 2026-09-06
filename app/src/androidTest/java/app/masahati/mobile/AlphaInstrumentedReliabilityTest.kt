package app.masahati.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class AlphaInstrumentedReliabilityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("masahati_v05.db")
        File(context.filesDir, "documents").deleteRecursively()
        File(context.cacheDir, "alpha-test").deleteRecursively()
    }

    @After
    fun tearDown() {
        context.deleteDatabase("masahati_v05.db")
        File(context.filesDir, "documents").deleteRecursively()
        File(context.cacheDir, "alpha-test").deleteRecursively()
    }

    @Test
    fun freshLaunchHasNoFakeSpacesAndDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                val db = MasahatiDatabase(activity.applicationContext)
                try {
                    assertTrue(db.listSpaces(false).isEmpty())
                    assertEquals("ok", db.databaseIntegrityStatus())
                    assertEquals(0, db.foreignKeyViolationCount())
                } finally {
                    db.close()
                }
            }
        }
    }

    @Test
    fun deletingSpaceCascadesEveryDatabaseObjectAndPhysicalFile() {
        val db = MasahatiDatabase(context)
        try {
            val spaceId = db.createSpace("مساحة اختبار")
            val document = File(context.filesDir, "documents/delete-me.txt").apply {
                parentFile?.mkdirs()
                writeText("Vertrag Nummer 42. Frist 30.09.2027")
            }
            val messageId = db.insertFile(
                spaceId = spaceId,
                role = "user",
                displayName = "Vertrag.txt",
                filePath = document.absolutePath,
                mimeType = "text/plain",
                ocrText = document.readText()
            )
            db.replaceDocumentChunks(messageId, listOf("Vertrag Nummer 42", "Frist 30.09.2027"))
            db.upsertDocumentMeta(
                DocumentMetaRow(
                    messageId = messageId,
                    smartTitle = "Vertrag 42",
                    docType = "contract",
                    organization = "Test GmbH",
                    personNames = null,
                    referenceNumber = "42",
                    amountText = null,
                    currency = null,
                    issueDate = "2026-09-01",
                    dueDate = "2027-09-30",
                    expiryDate = "2027-09-30",
                    actionRequired = true,
                    actionText = "Vertrag prüfen",
                    confidence = 0.98,
                    evidenceJson = null,
                    extractedJson = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            val actionId = db.createActionItem(
                spaceId = spaceId,
                messageId = messageId,
                kind = "deadline",
                title = "Vertrag prüfen",
                details = "Vor Ablauf",
                dueAt = System.currentTimeMillis() + 86_400_000L,
                sourceExcerpt = "Frist 30.09.2027"
            )
            db.createReminder(
                spaceId = spaceId,
                title = "Vertrag",
                body = "Vertrag prüfen",
                repeatRule = "none",
                dayOfWeek = null,
                hour = null,
                minute = null,
                nextFireAt = System.currentTimeMillis() + 3_600_000L,
                conditionActionId = actionId
            )
            db.updateAi(messageId, "document", "vertrag,frist", "Test summary", "{}")

            assertTrue(document.exists())
            assertTrue(db.listAllActionItems().isNotEmpty())
            assertTrue(db.listAllReminders().isNotEmpty())
            assertTrue(db.listAllMessageVersions().isNotEmpty())

            db.deleteSpace(spaceId)

            assertTrue(db.listSpaces(false).isEmpty())
            assertTrue(db.allMessagesIncludingTrash().isEmpty())
            assertTrue(db.listAllActionItems().isEmpty())
            assertTrue(db.listAllReminders().isEmpty())
            assertTrue(db.listAllMessageVersions().isEmpty())
            assertFalse(document.exists())
            assertEquals("ok", db.databaseIntegrityStatus())
            assertEquals(0, db.foreignKeyViolationCount())
        } finally {
            db.close()
        }
    }

    @Test
    fun fullBackupRoundTripRestoresFilesMetadataActionsRemindersAndHistory() {
        val sourceDb = MasahatiDatabase(context)
        val archive = ByteArrayOutputStream()
        val sourceFile: File
        try {
            val spaceId = sourceDb.createSpace("عقودي")
            sourceDb.insertText(spaceId, "user", "ذكرني بالعقد لاحقاً")
            sourceFile = File(context.filesDir, "documents/contract.txt").apply {
                parentFile?.mkdirs()
                writeText("Mietvertrag Test GmbH\nVertragsnummer MV-4488\nVertragsende 30.09.2027")
            }
            val messageId = sourceDb.insertFile(
                spaceId,
                "user",
                "Mietvertrag.txt",
                sourceFile.absolutePath,
                "text/plain",
                sourceFile.readText()
            )
            sourceDb.upsertDocumentMeta(
                DocumentMetaRow(
                    messageId = messageId,
                    smartTitle = "Mietvertrag MV-4488",
                    docType = "contract",
                    organization = "Test GmbH",
                    personNames = null,
                    referenceNumber = "MV-4488",
                    amountText = null,
                    currency = null,
                    issueDate = "2026-09-01",
                    dueDate = null,
                    expiryDate = "2027-09-30",
                    actionRequired = true,
                    actionText = "Vor Ablauf prüfen",
                    confidence = 0.99,
                    evidenceJson = "[{\"field\":\"expiry_date\",\"value\":\"2027-09-30\"}]",
                    extractedJson = "{\"doc_type\":\"contract\"}",
                    updatedAt = System.currentTimeMillis()
                )
            )
            val actionId = sourceDb.createActionItem(
                spaceId,
                messageId,
                "deadline",
                "Vor Ablauf prüfen",
                null,
                System.currentTimeMillis() + 2 * 86_400_000L,
                "Vertragsende 30.09.2027"
            )
            sourceDb.createReminder(
                spaceId,
                "Mietvertrag",
                "Vor Ablauf prüfen",
                "none",
                null,
                null,
                null,
                System.currentTimeMillis() + 86_400_000L,
                actionId
            )
            sourceDb.updateAi(messageId, "document", "mietvertrag,mv-4488", "Vertrag erkannt", "{}")
            AlphaDocumentProcessor.indexNewFile(sourceDb, messageId, sourceFile, sourceFile.readText())

            AlphaExporter.export(context, sourceDb, archive)
            assertTrue(archive.size() > 500)
        } finally {
            sourceDb.close()
        }

        File(context.filesDir, "documents").deleteRecursively()
        context.deleteDatabase("masahati_v05.db")

        val restoredDb = MasahatiDatabase(context)
        try {
            val summary = AlphaImporter.importZip(context, restoredDb, ByteArrayInputStream(archive.toByteArray()))
            assertEquals(1, summary.spaces)
            assertEquals(2, summary.messages)
            assertEquals(1, summary.files)
            assertEquals(1, summary.actions)
            assertEquals(1, summary.reminders)

            val spaces = restoredDb.listSpaces(false)
            assertEquals(1, spaces.size)
            assertEquals("عقودي", spaces.single().title)

            val messages = restoredDb.listMessages(spaces.single().id)
            assertEquals(2, messages.size)
            val fileMessage = messages.first { it.kind == "file" }
            assertTrue(File(fileMessage.filePath!!).isFile)
            assertTrue(fileMessage.ocrText.orEmpty().contains("MV-4488"))
            val meta = restoredDb.getDocumentMeta(fileMessage.id)
            assertNotNull(meta)
            assertEquals("MV-4488", meta?.referenceNumber)
            assertEquals("2027-09-30", meta?.expiryDate)
            assertTrue(restoredDb.listAllActionItems().isNotEmpty())
            assertTrue(restoredDb.listAllReminders().isNotEmpty())
            assertTrue(restoredDb.listAllMessageVersions().isNotEmpty())
            assertEquals("ok", restoredDb.databaseIntegrityStatus())
            assertEquals(0, restoredDb.foreignKeyViolationCount())

            val restoredPath = File(fileMessage.filePath!!)
            restoredDb.deleteSpace(spaces.single().id)
            assertFalse(restoredPath.exists())
            assertEquals("ok", restoredDb.databaseIntegrityStatus())
            assertEquals(0, restoredDb.foreignKeyViolationCount())
        } finally {
            restoredDb.close()
        }
    }

    @Test
    fun openSourceDocumentStackProcessesRealAndroidBitmapPhoneAndPdf() {
        val arabic = "هذا عقد رسمي يحتوي على موعد نهائي ومعلومات مهمة ويجب الاحتفاظ به للرجوع إليه لاحقاً. ".repeat(3)
        val german = "Dieser Vertrag enthält wichtige Informationen über die Frist und die erforderlichen Unterlagen. ".repeat(3)
        assertEquals("ar", OpenSourceDocumentTools.detectLanguage(arabic))
        assertEquals("de", OpenSourceDocumentTools.detectLanguage(german))

        val phones = OpenSourceDocumentTools.extractPhones(
            context,
            "Kontakt: 0631 1234567 oder +49 631 7654321"
        )
        assertTrue(phones.any { it.startsWith("+49") })

        val matrix = QRCodeWriter().encode("MASAHATI-REAL-QR", BarcodeFormat.QR_CODE, 320, 320)
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        for (y in 0 until 320) {
            for (x in 0 until 320) {
                bitmap.setPixel(x, y, if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt())
            }
        }
        try {
            assertTrue(OpenSourceDocumentTools.decodeBarcodes(bitmap).contains("MASAHATI-REAL-QR"))
        } finally {
            bitmap.recycle()
        }

        PDFBoxResourceLoader.init(context)
        val pdfFile = File(context.cacheDir, "alpha-test/real-text.pdf").apply { parentFile?.mkdirs() }
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(40f, 700f)
                stream.showText("Contract deadline 30.09.2027")
                stream.endText()
            }
            pdf.save(pdfFile)
        }
        val extracted = OpenSourceDocumentTools.extractPdfText(context, pdfFile)
        assertTrue(extracted.contains("30.09.2027"))
    }
}
