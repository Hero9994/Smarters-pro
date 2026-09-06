package app.masahati.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import app.masahati.mobile.ai.LocalModelPackManager
import app.masahati.mobile.ai.LocalModelSpec
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

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

    @Test
    fun reminderDeliveryWritesWhatsappStyleMessageExactlyOnce() {
        val db = MasahatiDatabase(context)
        val spaceId: Long
        val reminderId: Long
        val scheduled = System.currentTimeMillis() - 1_000L
        try {
            spaceId = db.createSpace("تذكيرات")
            reminderId = db.createReminder(
                spaceId = spaceId,
                title = "ديزل",
                body = "ذكرني اعبي ديزل اليوم الساعة 20.30",
                repeatRule = "none",
                dayOfWeek = null,
                hour = null,
                minute = null,
                nextFireAt = scheduled
            )
        } finally {
            db.close()
        }

        ReminderDelivery.deliver(context, reminderId)
        ReminderDelivery.deliver(context, reminderId)

        val verify = MasahatiDatabase(context)
        try {
            val assistant = verify.listMessages(spaceId).filter { it.role == "assistant" }
            assertEquals(1, assistant.size)
            assertTrue(assistant.single().text.startsWith("تذكير: اليوم الساعة"))
            assertTrue(assistant.single().text.contains("اعبي ديزل"))
            assertFalse(verify.getReminder(reminderId)?.enabled ?: true)
            assertEquals("ok", verify.databaseIntegrityStatus())
        } finally {
            verify.close()
        }
    }

    @Test
    fun removingGeneratedActionRetiresItsConditionalReminder() {
        val db = MasahatiDatabase(context)
        try {
            val spaceId = db.createSpace("عقد")
            val file = File(context.filesDir, "documents/action.txt").apply {
                parentFile?.mkdirs()
                writeText("Contract action")
            }
            val messageId = db.insertFile(spaceId, "user", "action.txt", file.absolutePath, "text/plain", file.readText())
            val actionId = db.createActionItem(
                spaceId, messageId, "deadline", "أرسل الورقة", null,
                System.currentTimeMillis() + 86_400_000L, "deadline"
            )
            val reminderId = db.createReminder(
                spaceId, "شرطي", "ذكرني إذا بقي الإجراء مفتوحاً", "none",
                null, null, null, System.currentTimeMillis() + 86_400_000L, actionId
            )
            assertTrue(db.getReminder(reminderId)?.enabled == true)

            db.clearGeneratedActionItemsForMessage(messageId)

            assertTrue(db.getActionItem(actionId) == null)
            val reminder = db.getReminder(reminderId)
            assertNotNull(reminder)
            assertFalse(reminder?.enabled ?: true)
            assertTrue(reminder?.conditionActionId == null)
            assertEquals(0, db.foreignKeyViolationCount())
        } finally {
            db.close()
        }
    }


    @Test
    fun upgradeFromV6PreservesRealUserDataAndBuildsAlphaSchema() {
        context.deleteDatabase("masahati_v05.db")
        val dbFile = context.getDatabasePath("masahati_v05.db").apply { parentFile?.mkdirs() }

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { old ->
            old.execSQL(
                """
                CREATE TABLE spaces(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  title TEXT NOT NULL,
                  pinned INTEGER NOT NULL DEFAULT 0,
                  archived INTEGER NOT NULL DEFAULT 0,
                  focus_message_id INTEGER,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            old.execSQL(
                """
                CREATE TABLE messages(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  space_id INTEGER NOT NULL,
                  role TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  text TEXT NOT NULL DEFAULT '',
                  file_path TEXT,
                  mime_type TEXT,
                  display_name TEXT,
                  ocr_text TEXT,
                  classification TEXT,
                  tags TEXT,
                  summary TEXT,
                  starred INTEGER NOT NULL DEFAULT 0,
                  ai_json TEXT,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            old.execSQL(
                """
                CREATE TABLE reminders(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  space_id INTEGER NOT NULL,
                  title TEXT NOT NULL,
                  body TEXT NOT NULL,
                  repeat_rule TEXT NOT NULL DEFAULT 'none',
                  day_of_week INTEGER,
                  hour INTEGER,
                  minute INTEGER,
                  next_fire_at INTEGER,
                  enabled INTEGER NOT NULL DEFAULT 1,
                  delivered_at INTEGER,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            val now = System.currentTimeMillis()
            old.execSQL(
                "INSERT INTO spaces(id,title,pinned,archived,created_at,updated_at) VALUES(1,'عقودي',1,0,?,?)",
                arrayOf(now, now)
            )
            old.execSQL(
                "INSERT INTO messages(id,space_id,role,kind,text,ocr_text,display_name,starred,created_at) VALUES(7,1,'user','file','',?,'vertrag.pdf',1,?)",
                arrayOf("Mietvertrag MV-4488 Vertragsende 30.09.2027", now)
            )
            old.execSQL(
                "INSERT INTO reminders(id,space_id,title,body,repeat_rule,next_fire_at,enabled,created_at) VALUES(3,1,'عقد','راجع العقد','none',?,1,?)",
                arrayOf(now + 86_400_000L, now)
            )
            old.version = 6
        }

        val upgraded = MasahatiDatabase(context)
        try {
            val spaces = upgraded.listSpaces(false)
            assertEquals(1, spaces.size)
            assertEquals("عقودي", spaces.single().title)
            val messages = upgraded.listMessages(spaces.single().id)
            assertEquals(1, messages.size)
            assertEquals("vertrag.pdf", messages.single().displayName)
            assertTrue(messages.single().starred)
            assertTrue(messages.single().ocrText.orEmpty().contains("MV-4488"))
            assertEquals(1, upgraded.listAllReminders().size)

            // Exercise columns/tables added after v6, not just opening the DB.
            AlphaDocumentProcessor.indexNewFile(
                upgraded,
                messages.single().id,
                File(context.cacheDir, "alpha-test/migrated.txt").apply {
                    parentFile?.mkdirs()
                    writeText("Mietvertrag MV-4488 Vertragsende 30.09.2027")
                },
                messages.single().ocrText
            )
            upgraded.upsertDocumentMeta(
                DocumentMetaRow(
                    messageId = messages.single().id,
                    smartTitle = "Mietvertrag MV-4488",
                    docType = "contract",
                    organization = null,
                    personNames = null,
                    referenceNumber = "MV-4488",
                    amountText = null,
                    currency = null,
                    issueDate = null,
                    dueDate = null,
                    expiryDate = "2027-09-30",
                    actionRequired = false,
                    actionText = null,
                    confidence = 0.99,
                    evidenceJson = null,
                    extractedJson = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            upgraded.updateAi(messages.single().id, "document", "vertrag", "Vertrag", "{}")
            assertNotNull(upgraded.getDocumentMeta(messages.single().id))
            assertTrue(upgraded.listMessageVersions(messages.single().id).isNotEmpty())
            assertEquals("ok", upgraded.databaseIntegrityStatus())
            assertEquals(0, upgraded.foreignKeyViolationCount())
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun upgradeFromV2AlsoSucceedsWithoutDuplicateColumnCrashes() {
        context.deleteDatabase("masahati_v05.db")
        val dbFile = context.getDatabasePath("masahati_v05.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { old ->
            old.execSQL(
                """
                CREATE TABLE spaces(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  title TEXT NOT NULL,
                  pinned INTEGER NOT NULL DEFAULT 0,
                  archived INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            old.execSQL(
                """
                CREATE TABLE messages(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  space_id INTEGER NOT NULL,
                  role TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  text TEXT NOT NULL DEFAULT '',
                  file_path TEXT,
                  mime_type TEXT,
                  display_name TEXT,
                  ocr_text TEXT,
                  classification TEXT,
                  tags TEXT,
                  summary TEXT,
                  ai_json TEXT,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            val now = System.currentTimeMillis()
            old.execSQL(
                "INSERT INTO spaces(id,title,pinned,archived,created_at,updated_at) VALUES(1,'قديم',0,0,?,?)",
                arrayOf(now, now)
            )
            old.execSQL(
                "INSERT INTO messages(id,space_id,role,kind,text,created_at) VALUES(1,1,'user','text','بيانات قديمة مهمة',?)",
                arrayOf(now)
            )
            old.version = 2
        }

        val upgraded = MasahatiDatabase(context)
        try {
            assertEquals("قديم", upgraded.listSpaces(false).single().title)
            assertEquals("بيانات قديمة مهمة", upgraded.listMessages(1).single().text)
            assertEquals("ok", upgraded.databaseIntegrityStatus())
            assertEquals(0, upgraded.foreignKeyViolationCount())
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun trashRestoreHistoryAndHardDeleteRemainConsistent() {
        val db = MasahatiDatabase(context)
        try {
            val spaceId = db.createSpace("ملفات")
            val file = File(context.filesDir, "documents/history.txt").apply {
                parentFile?.mkdirs()
                writeText("original contract text")
            }
            val messageId = db.insertFile(
                spaceId, "user", "Scan-1.txt", file.absolutePath, "text/plain", file.readText()
            )

            db.renameMessageDisplayName(messageId, "عقد-ذكي.txt")
            val renameVersions = db.listMessageVersions(messageId)
            assertTrue(renameVersions.any { it.reason == "smart_rename" })
            assertEquals("عقد-ذكي.txt", db.getMessage(messageId)?.displayName)

            db.deleteMessage(messageId)
            assertTrue(file.exists())
            assertTrue(db.listTrash().any { it.id == messageId })
            assertTrue(db.listMessages(spaceId).none { it.id == messageId })

            db.restoreMessage(messageId)
            assertTrue(db.listMessages(spaceId).any { it.id == messageId })
            assertTrue(file.exists())

            val originalVersion = db.listMessageVersions(messageId)
                .firstOrNull { it.displayName == "Scan-1.txt" }
            assertNotNull(originalVersion)
            assertTrue(db.restoreMessageVersion(originalVersion!!.id))
            assertEquals("Scan-1.txt", db.getMessage(messageId)?.displayName)

            db.hardDeleteMessage(messageId)
            assertFalse(file.exists())
            assertTrue(db.allMessagesIncludingTrash().none { it.id == messageId })
            assertTrue(db.listMessageVersions(messageId).isEmpty())
            assertEquals("ok", db.databaseIntegrityStatus())
            assertEquals(0, db.foreignKeyViolationCount())
        } finally {
            db.close()
        }
    }

    @Test
    fun sixHundredMessageSpaceSearchPagingAndIntegrityStayCorrect() {
        val db = MasahatiDatabase(context)
        try {
            val spaceId = db.createSpace("أرشيف كبير")
            repeat(600) { index ->
                val text = if (index == 437) {
                    "وثيقة خاصة رقم ZX-UNIQUE-437 تتعلق بتأمين السيارة Versicherung"
                } else {
                    "ملاحظة يومية رقم $index عن العمل والمهام"
                }
                db.insertText(spaceId, "user", text)
            }
            assertEquals(600, db.countMessages(spaceId))
            assertEquals(150, db.listRecentMessages(spaceId, 150).size)
            assertEquals(600, db.listRecentMessages(spaceId, 1000).size)

            val hit = db.search("ZX UNIQUE 437 تأمين", 20)
            assertTrue(hit.any { it.text.contains("ZX-UNIQUE-437") })
            val germanHit = db.search("Versicherung", 20)
            assertTrue(germanHit.any { it.text.contains("ZX-UNIQUE-437") })

            assertEquals("ok", db.databaseIntegrityStatus())
            assertEquals(0, db.foreignKeyViolationCount())
        } finally {
            db.close()
        }
    }

    @Test
    fun scannerShadowCorrectionImprovesSyntheticShadowAndLeavesCleanPageUntouched() {
        val clean = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        clean.eraseColor(Color.rgb(240, 240, 240))
        val cleanResult = DocumentImageEnhancer.flattenDocumentShadows(clean)
        assertSame(clean, cleanResult)

        val shadow = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        for (y in 0 until shadow.height) {
            for (x in 0 until shadow.width) {
                val base = if (x < 300) 150 else 240
                shadow.setPixel(x, y, Color.rgb(base, base, base))
            }
        }
        val before = averageLuminance(shadow, 40, 40, 250, 700)
        val corrected = DocumentImageEnhancer.flattenDocumentShadows(shadow)
        try {
            assertFalse(corrected === shadow)
            val after = averageLuminance(corrected, 40, 40, 250, 700)
            assertTrue("shadow region should brighten: before=$before after=$after", after > before + 8.0)
            val brightSide = averageLuminance(corrected, 350, 40, 200, 700)
            assertTrue(brightSide <= 253.0)
        } finally {
            if (corrected !== shadow) corrected.recycle()
            shadow.recycle()
            clean.recycle()
        }
    }

    @Test
    fun encryptedBackupAuthenticatesThenRoundTripsRealDatabase() {
        val db = MasahatiDatabase(context)
        val encrypted = ByteArrayOutputStream()
        try {
            val spaceId = db.createSpace("نسخة مشفرة")
            db.insertText(spaceId, "user", "بيانات لا يجب فقدانها")
            AlphaBackupCrypto.encrypt("StrongPass123".toCharArray(), encrypted) { out ->
                AlphaExporter.export(context, db, out)
            }
        } finally {
            db.close()
        }

        context.deleteDatabase("masahati_v05.db")
        val plain = ByteArrayOutputStream()
        AlphaBackupCrypto.decrypt(
            "StrongPass123".toCharArray(),
            ByteArrayInputStream(encrypted.toByteArray())
        ) { input -> input.copyTo(plain) }

        val restored = MasahatiDatabase(context)
        try {
            val summary = AlphaImporter.importZip(context, restored, ByteArrayInputStream(plain.toByteArray()))
            assertEquals(1, summary.spaces)
            assertEquals(1, summary.messages)
            val space = restored.listSpaces(false).single()
            assertEquals("نسخة مشفرة", space.title)
            assertEquals("بيانات لا يجب فقدانها", restored.listMessages(space.id).single().text)
            assertEquals("ok", restored.databaseIntegrityStatus())
        } finally {
            restored.close()
        }
    }

    @Test
    fun morningBriefCombinesActionsRemindersAndUpcomingExpiry() {
        val db = MasahatiDatabase(context)
        try {
            val zone = ZoneId.of("Europe/Berlin")
            val now = ZonedDateTime.of(2026, 9, 6, 7, 30, 0, 0, zone)
            val nowMs = now.toInstant().toEpochMilli()
            val spaceId = db.createSpace("اليوم")
            val messageId = db.insertText(spaceId, "user", "عقد")
            db.createActionItem(
                spaceId, messageId, "deadline", "أرسل الاعتراض", null,
                now.plusHours(5).toInstant().toEpochMilli(), "Frist"
            )
            db.createReminder(
                spaceId, "ديزل", "اعبي ديزل", "none", null, null, null,
                now.plusHours(2).toInstant().toEpochMilli()
            )
            db.upsertDocumentMeta(
                DocumentMetaRow(
                    messageId = messageId,
                    smartTitle = "عقد التأمين",
                    docType = "contract",
                    organization = null,
                    personNames = null,
                    referenceNumber = null,
                    amountText = null,
                    currency = null,
                    issueDate = null,
                    dueDate = null,
                    expiryDate = LocalDate.of(2026, 9, 16).toString(),
                    actionRequired = false,
                    actionText = null,
                    confidence = 0.9,
                    evidenceJson = null,
                    extractedJson = null,
                    updatedAt = nowMs
                )
            )
            val brief = MorningBriefBuilder.build(db, now)
            assertNotNull(brief)
            assertTrue(brief!!.contains("أرسل الاعتراض"))
            assertTrue(brief.contains("اعبي ديزل"))
            assertTrue(brief.contains("عقد التأمين"))
            assertTrue(brief.contains("بعد 10 يوم"))
        } finally {
            db.close()
        }
    }

    @Test
    fun repeatedActivityLaunchAndCloseDoesNotSeedOrCorruptDatabase() {
        repeat(3) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
            }
        }
        val db = MasahatiDatabase(context)
        try {
            assertTrue(db.listSpaces(false).isEmpty())
            assertEquals("ok", db.databaseIntegrityStatus())
            assertEquals(0, db.foreignKeyViolationCount())
        } finally {
            db.close()
        }
    }

    private fun averageLuminance(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int
    ): Double {
        var sum = 0L
        var count = 0L
        val step = 8
        var y = startY
        while (y < (startY + height).coerceAtMost(bitmap.height)) {
            var x = startX
            while (x < (startX + width).coerceAtMost(bitmap.width)) {
                val color = bitmap.getPixel(x, y)
                sum += (Color.red(color) * 299L + Color.green(color) * 587L + Color.blue(color) * 114L) / 1000L
                count++
                x += step
            }
            y += step
        }
        return if (count == 0L) 0.0 else sum.toDouble() / count.toDouble()
    }


    @Test
    fun localModelPackRejectsTruncatedFilesEvenWithVerificationMarker() {
        val spec = LocalModelSpec(
            id = "tiny-test",
            displayName = "Tiny test",
            fileName = "tiny-test-model.bin",
            downloadUrl = "https://example.invalid/model.bin",
            expectedBytes = 4L,
            sha256 = "test-checksum",
            maxTokens = 32,
            supportsVision = false
        )
        val manager = LocalModelPackManager(context)
        val file = manager.modelFile(spec)
        val marker = File(file.absolutePath + ".verified")
        try {
            file.parentFile?.mkdirs()
            marker.writeText(spec.sha256)
            file.writeBytes(byteArrayOf(1, 2))
            assertFalse(manager.isInstalled(spec))

            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertTrue(manager.isInstalled(spec))
        } finally {
            file.delete()
            marker.delete()
        }
    }

}
