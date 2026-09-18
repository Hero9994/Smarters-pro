package app.masahati.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentActionsInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MasahatiDatabase
    @Before fun setUp() { context.deleteDatabase("masahati_v05.db"); db = MasahatiDatabase(context) }
    @After fun tearDown() { db.close(); context.deleteDatabase("masahati_v05.db") }
    private fun action(type: String, args: JSONObject = JSONObject(), confirmation: Boolean = false) =
        JSONArray().put(JSONObject().put("type",type).put("args",args).put("requires_confirmation",confirmation))

    @Test fun ignoresHallucinatedActionsAndDoesNotFallbackToWrongSpace() {
        val space = db.createSpace("أصلية")
        val question = db.insertText(space, "user", "كم كتاب بقي؟")
        val executor = AgentActionExecutor(context, db)
        executor.execute(db.getMessage(question)!!, action("create_space",JSONObject().put("name","مختلقة")),null,null,null)
        assertNull(db.findSpaceByTitle("مختلقة"))
        val archive = db.insertText(space, "user", "أرشف مساحة غير موجودة")
        executor.execute(db.getMessage(archive)!!,action("archive_space",JSONObject().put("space_name","غير موجودة")),null,null,null)
        assertFalse(db.getSpace(space)!!.archived)
    }

    @Test fun commandMovesCapturedItemEvenIfAnotherMessageArrivesLater() {
        val from = db.createSpace("أوراقي")
        val to = db.createSpace("عقودي")
        val file = db.insertFile(from,"user","old.pdf","/old","application/pdf","old text")
        val command = db.insertText(from,"user","انقل آخر ملف إلى مساحة عقودي")
        val later = db.insertFile(from,"user","new.pdf","/new","application/pdf","new text")
        val result = LocalCommandParser.analyze(db.getMessage(command)!!.text,"أوراقي")!!
        val executed = AgentActionExecutor(context, db).execute(db.getMessage(command)!!,result.optJSONArray("actions"),file,file,file)
        assertEquals(to, db.getMessage(file)!!.spaceId)
        assertEquals(from, db.getMessage(later)!!.spaceId)
        assertEquals(from, db.getMessage(command)!!.spaceId)
        assertTrue(executed.note.contains("تم نقل"))
    }

    @Test fun createsSpaceAndRenamesFileLocallyWithNoModelOrDefaultData() {
        val space = db.createSpace("أوراقي")
        val file = db.insertFile(space,"user","scan.pdf","/scan","application/pdf","OCR")
        for (text in listOf("اعمل مساحة باسم الدراسة", "غيّر اسم آخر ملف إلى عقد البيت")) {
            val id = db.insertText(space,"user",text)
            val parsed = LocalCommandParser.analyze(text,"أوراقي")!!
            AgentActionExecutor(context, db).execute(db.getMessage(id)!!,parsed.optJSONArray("actions"),file,file,file)
        }
        assertNotNull(db.findSpaceByTitle("الدراسة"))
        assertEquals("عقد البيت", db.getMessage(file)!!.displayName)
        assertEquals(2, db.listSpaces(false).size)
        assertNull(LocalCommandParser.analyze("لا تنقل آخر ملف إلى مساحة الدراسة", "أوراقي"))
    }

    @Test fun requiresConfirmationBeforeAnyProposedMutation() {
        val space = db.createSpace("أوراقي")
        val id = db.insertText(space,"user","اعمل مساحة باسم جديدة")
        val result = AgentActionExecutor(context, db).execute(db.getMessage(id)!!,
            action("create_space",JSONObject().put("name","جديدة"),true),null,null,null)
        assertNull(db.findSpaceByTitle("جديدة"))
        assertTrue(result.note.contains("لم يُنفّذ"))
    }
}
