package app.masahati.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryReliabilityTest {
    private lateinit var context: Context
    private lateinit var db: MasahatiDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("masahati_v05.db")
        db = MasahatiDatabase(context)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase("masahati_v05.db")
    }

    @Test fun fileConsentSurvivesReopenAndRemainsIsolatedToItsDocument() {
        val space = db.createSpace("مستنداتي")
        val first = db.insertFile(space, "user", "first.pdf", "/test/first", "application/pdf", "private")
        val second = db.insertFile(space, "user", "second.pdf", "/test/second", "application/pdf", "other")
        assertTrue(db.hasLocalOnlyDocuments(space))
        db.setDocumentCloudAnalysisAllowed(first, true)
        db.close()
        db = MasahatiDatabase(context)
        assertTrue(db.getMessage(first)!!.cloudAnalysisAllowed)
        assertFalse(db.getMessage(second)!!.cloudAnalysisAllowed)
        assertTrue(db.hasLocalOnlyDocuments(space))
        db.setDocumentCloudAnalysisAllowed(second, true)
        assertFalse(db.hasLocalOnlyDocuments(space))
        db.setDocumentCloudAnalysisAllowed(first, false)
        assertTrue(db.hasLocalOnlyDocuments(space))
    }

    @Test fun upgradingVersion12PreservesContentAndDoesNotInventConsent() {
        val definitions = db.readableDatabase.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'android_metadata'",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        db.close()
        context.deleteDatabase("masahati_v05.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("masahati_v05.db"), null).use { old ->
            definitions.forEach { sql ->
                old.execSQL(sql.replace("cloud_analysis_allowed INTEGER NOT NULL DEFAULT 0,", ""))
            }
            old.execSQL("INSERT INTO spaces(id,title,created_at,updated_at) VALUES(1,'old',1,1)")
            old.execSQL("INSERT INTO messages(id,space_id,role,kind,ocr_text,created_at) VALUES(1,1,'user','file','original',1)")
            old.version = 12
        }
        db = MasahatiDatabase(context)
        val row = db.getMessage(1)!!
        assertEquals("original", row.ocrText)
        assertFalse(row.cloudAnalysisAllowed)
        assertEquals(13, db.readableDatabase.version)
        assertEquals("ok", db.databaseIntegrityStatus())
    }

    @Test fun aiHistoryExcludesQuestionAndMessagesQueuedAfterIt() {
        val firstSpace = db.createSpace("الأولى")
        val secondSpace = db.createSpace("الثانية")
        val before = db.insertText(firstSpace, "user", "السياق السابق")
        val question = db.insertText(firstSpace, "user", "السؤال")
        db.insertText(firstSpace, "user", "رسالة لاحقة")
        db.insertText(secondSpace, "user", "سياق مختلف")
        val rows = db.recentForAi(firstSpace, 20, db.getMessage(question))
        assertEquals(listOf(before), rows.map { it.id })
    }

    @Test fun analysisStaysWithSourceMessageWhenAnotherChatIsOpen() {
        val original = db.createSpace("الأصلية")
        val other = db.createSpace("الثانية")
        val file = db.insertFile(original, "user", "Vertrag.pdf", "/test/contract", "application/pdf", "Vertragsende 30.09.2027")
        db.setFocusedMessage(original, file)
        val question = db.insertText(original, "user", "متى بينتهي العقد؟")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("openSpace", java.lang.Long.TYPE).apply {
                    isAccessible = true
                }.invoke(activity, other)
                MainActivity::class.java.getDeclaredMethod(
                    "analyzeWithAgent", java.lang.Long.TYPE, String::class.java, String::class.java
                ).apply { isAccessible = true }.invoke(activity, question, "متى بينتهي العقد؟", "الأصلية")
            }
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline && db.listMessages(original).none { it.role == "assistant" }) {
                Thread.sleep(50L)
            }
            val reply = db.listMessages(original).single { it.role == "assistant" }
            assertTrue(reply.text.contains("30.09.2027"))
            assertTrue(db.listMessages(other).isEmpty())
            scenario.onActivity { activity ->
                val current = MainActivity::class.java.getDeclaredField("currentSpaceId").apply { isAccessible = true }
                assertEquals(other, current.get(activity))
            }
        }
    }

    @Test fun deletedFocusedDocumentIsNotUsedForAnswers() {
        val space = db.createSpace("أوراق")
        val file = db.insertFile(space, "user", "old.pdf", "/test/old", "application/pdf", "secret")
        db.setFocusedMessage(space, file)
        db.deleteMessage(file)
        // Also protect against a stale focus restored from an older backup.
        db.setFocusedMessage(space, file)
        assertNull(db.focusedDocument(space))
        assertTrue(db.hasLocalOnlyDocuments(space))
    }

    @Test fun assistantReplyPreservesTheDraftAndComposerFocus() {
        val space = db.createSpace("مسودة")
        val file = db.insertFile(space, "user", "contract.pdf", "/test/contract", "application/pdf", "Vertragsende 30.09.2027")
        db.setFocusedMessage(space, file)
        val question = db.insertText(space, "user", "متى بينتهي العقد؟")
        lateinit var input: android.widget.EditText
        val composerField = MainActivity::class.java.getDeclaredField("composer").apply { isAccessible = true }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("openSpace", java.lang.Long.TYPE).apply {
                    isAccessible = true
                }.invoke(activity, space)
                MainActivity::class.java.getDeclaredMethod(
                    "analyzeWithAgent", java.lang.Long.TYPE, String::class.java, String::class.java
                ).apply { isAccessible = true }.invoke(activity, question, "متى بينتهي العقد؟", "مسودة")
                input = composerField.get(activity) as android.widget.EditText
                input.setText("مسودة لم أرسلها بعد")
                input.requestFocus()
                input.setSelection(5)
            }
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline && db.listMessages(space).none { it.role == "assistant" }) {
                Thread.sleep(50L)
            }
            assertTrue(db.listMessages(space).any { it.role == "assistant" })
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertSame(input, composerField.get(activity))
                assertEquals("مسودة لم أرسلها بعد", input.text.toString())
                assertEquals(5, input.selectionStart)
                assertTrue(input.hasFocus())
            }
        }
    }

    @Test fun staleBackupCannotDeliverTheNextOccurrenceEarly() {
        val space = db.createSpace("تنبيهات")
        val future = System.currentTimeMillis() + 86_400_000L
        val reminder = db.createReminder(space, "دواء", "دواء", "daily", null, 9, 0, future)
        ReminderDelivery.deliver(context, reminder)
        assertTrue(db.listMessages(space).isEmpty())
        assertNull(db.getReminder(reminder)!!.deliveredAt)
        assertEquals(future, db.getReminder(reminder)!!.nextFireAt)
    }

    @Test fun deliveryClaimRequiresTheCurrentDueOccurrence() {
        val space = db.createSpace("تنبيهات")
        val now = System.currentTimeMillis()
        val scheduled = now - 1000L
        val id = db.createReminder(space, "اختبار", "مهمة", "none", null, null, null, scheduled)
        assertFalse(db.tryMarkReminderDelivered(id, scheduled - 1000L, now))
        assertTrue(db.tryMarkReminderDelivered(id, scheduled, now))
        assertFalse(db.tryMarkReminderDelivered(id, scheduled, now + 1))
    }
}
