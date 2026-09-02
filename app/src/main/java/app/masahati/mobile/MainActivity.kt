package app.masahati.mobile

import android.annotation.SuppressLint
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.masahati.mobile.data.MasahatiDatabase
import app.masahati.mobile.data.MessageRecord
import app.masahati.mobile.data.SpaceSummary
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {
    private val database by lazy { MasahatiDatabase(this) }
    private var currentSpaceId: Long?=null
    private var showingArchive=false
    private var searchQuery=""
    private var homeAdapter: SpaceAdapter?=null
    private var messageAdapter: MessageAdapter?=null
    private var messageList: ListView?=null
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val spaceId = currentSpaceId
        if (uri != null && spaceId != null) importAttachment(spaceId, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentSpaceId != null) {
                    currentSpaceId = null
                    showHome()
                } else {
                    finish()
                }
            }
        })
        currentSpaceId=savedInstanceState?.getLong(KEY_SPACE_ID,NO_SPACE)?.takeIf { it!=NO_SPACE }
        val requested=currentSpaceId
        if(requested!=null && database.getSpace(requested)!=null) showSpace(requested) else { currentSpaceId=null;showHome() }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState);outState.putLong(KEY_SPACE_ID,currentSpaceId?:NO_SPACE) }
    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        window.statusBarColor = getColor(R.color.masahati_teal_dark)
        window.navigationBarColor = getColor(R.color.masahati_surface)
    }

    override fun onDestroy() { database.close();super.onDestroy() }

    private fun showHome() {
        currentSpaceId=null
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(getColor(R.color.masahati_surface));layoutDirection=View.LAYOUT_DIRECTION_RTL }
        root.addView(buildHomeToolbar());root.addView(buildSearchBox())
        val filters=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.START;setPadding(dp(16),dp(6),dp(16),dp(8)) }
        filters.addView(filterChip("المساحات",!showingArchive){showingArchive=false;showHome()})
        filters.addView(filterChip("الأرشيف",showingArchive){showingArchive=true;showHome()})
        root.addView(filters)
        val list=ListView(this).apply { divider=null;dividerHeight=0;setBackgroundColor(Color.TRANSPARENT);isVerticalScrollBarEnabled=false }
        homeAdapter=SpaceAdapter(loadSpaces()).also { list.adapter=it }
        list.setOnItemClickListener { _,_,position,_ -> homeAdapter?.getItem(position)?.let { showSpace(it.id) } }
        list.setOnItemLongClickListener { _,_,position,_ -> homeAdapter?.getItem(position)?.let(::showSpaceActions);true }
        root.addView(list,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root)
    }

    private fun buildHomeToolbar(): View {
        val bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(14),dp(12),dp(10)) }
        val title=TextView(this).apply { text="مساحاتي";textSize=28f;setTextColor(getColor(R.color.masahati_teal));setTypeface(typeface,Typeface.BOLD) }
        bar.addView(title,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        val add=Button(this).apply { text="＋";textSize=24f;contentDescription="إنشاء مساحة جديدة";minWidth=dp(52);setOnClickListener { promptCreateSpace() } }
        bar.addView(add,LinearLayout.LayoutParams(dp(58),dp(52)))
        return bar
    }

    private fun buildSearchBox(): View {
        val container=LinearLayout(this).apply { setPadding(dp(16),dp(4),dp(16),dp(4)) }
        val search=EditText(this).apply {
            hint="ابحث في المساحات والمحتوى";setSingleLine(true);textSize=16f;setPadding(dp(18),dp(12),dp(18),dp(12));background=rounded(Color.WHITE,20f,0xFFE0E8E6.toInt());setText(searchQuery);setSelection(text.length)
            addTextChangedListener(object:TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=Unit;override fun afterTextChanged(s:Editable?){searchQuery=s?.toString().orEmpty();homeAdapter?.replace(loadSpaces())} })
        }
        container.addView(search,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));return container
    }

    private fun filterChip(text:String,selected:Boolean,onClick:()->Unit): View=TextView(this).apply {
        this.text=text;textSize=14f;gravity=Gravity.CENTER;setPadding(dp(16),dp(8),dp(16),dp(8));setTextColor(if(selected)Color.WHITE else getColor(R.color.masahati_teal_dark));background=rounded(if(selected)getColor(R.color.masahati_teal)else Color.WHITE,18f,if(selected)null else 0xFFD8E4E1.toInt());setOnClickListener { onClick() };layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd=dp(8) }
    }

    private fun loadSpaces(): List<SpaceSummary> = database.listSpaces(searchQuery,showingArchive)

    private fun promptCreateSpace() {
        val input=EditText(this).apply { hint="اسم المساحة";setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("مساحة جديدة").setView(wrapDialogInput(input)).setNegativeButton("إلغاء",null).setPositiveButton("إنشاء") { _,_ -> val title=input.text.toString().trim();if(title.isNotEmpty())showSpace(database.createSpace(title)) }.show()
    }

    private fun showSpaceActions(space: SpaceSummary) {
        val actions=arrayOf("فتح","إعادة تسمية",if(space.pinned)"إلغاء التثبيت" else "تثبيت",if(space.archived)"إرجاع من الأرشيف" else "أرشفة","حذف")
        AlertDialog.Builder(this).setTitle(space.title).setItems(actions) { _,which ->
            when(which) {
                0->showSpace(space.id)
                1->promptRenameSpace(space)
                2->{database.setPinned(space.id,!space.pinned);if(currentSpaceId==space.id)showSpace(space.id)else refreshHome()}
                3->{database.setArchived(space.id,!space.archived);if(currentSpaceId==space.id){currentSpaceId=null;showHome()}else refreshHome()}
                4->confirmDeleteSpace(space)
            }
        }.show()
    }

    private fun promptRenameSpace(space: SpaceSummary) {
        val input=EditText(this).apply { setText(space.title);setSelection(text.length);setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("إعادة تسمية").setView(wrapDialogInput(input)).setNegativeButton("إلغاء",null).setPositiveButton("حفظ") { _,_ -> val title=input.text.toString().trim();if(title.isNotEmpty()){database.renameSpace(space.id,title);if(currentSpaceId==space.id)showSpace(space.id)else refreshHome()} }.show()
    }

    private fun confirmDeleteSpace(space: SpaceSummary) {
        AlertDialog.Builder(this).setTitle("حذف ${space.title}؟").setMessage("سيتم حذف الرسائل والملفات المحلية داخل هذه المساحة.").setNegativeButton("إلغاء",null).setPositiveButton("حذف") { _,_ -> database.deleteSpace(space.id);if(currentSpaceId==space.id){currentSpaceId=null;showHome()}else refreshHome() }.show()
    }

    private fun refreshHome() { homeAdapter?.replace(loadSpaces()) }

    private fun showSpace(spaceId: Long) {
        val space=database.getSpace(spaceId)?:run { showHome();return }
        currentSpaceId=spaceId
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(getColor(R.color.masahati_surface));layoutDirection=View.LAYOUT_DIRECTION_RTL }
        root.addView(buildSpaceToolbar(spaceId,space.title))
        val list=ListView(this).apply { divider=null;dividerHeight=dp(5);setPadding(dp(10),dp(8),dp(10),dp(8));clipToPadding=false;transcriptMode=ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL }
        messageList=list;messageAdapter=MessageAdapter(database.listMessages(spaceId)).also { list.adapter=it }
        list.setOnItemClickListener { _,_,position,_ -> messageAdapter?.getItem(position)?.takeIf { it.type=="file" }?.let(::openAttachment) }
        list.setOnItemLongClickListener { _,_,position,_ -> messageAdapter?.getItem(position)?.let(::showMessageActions);true }
        root.addView(list,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));root.addView(buildComposer(spaceId));setContentView(root);scrollMessagesToBottom()
    }

    private fun buildSpaceToolbar(spaceId: Long,title:String): View {
        val bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(10),dp(12),dp(8));setBackgroundColor(Color.WHITE) }
        val back=Button(this).apply { text="←";textSize=22f;contentDescription="رجوع";setOnClickListener { currentSpaceId=null;showHome() } };bar.addView(back,LinearLayout.LayoutParams(dp(54),dp(50)))
        val titleView=TextView(this).apply { text=title;textSize=21f;setTextColor(getColor(R.color.masahati_text));setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER_VERTICAL or Gravity.START;setPadding(dp(10),0,dp(10),0) };bar.addView(titleView,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f))
        val more=Button(this).apply { text="⋮";textSize=22f;contentDescription="خيارات المساحة";setOnClickListener { database.getSpace(spaceId)?.let { r -> showSpaceActions(SpaceSummary(r.id,r.title,r.pinned,r.archived,null,System.currentTimeMillis())) } } };bar.addView(more,LinearLayout.LayoutParams(dp(54),dp(50)))
        return bar
    }

    private fun buildComposer(spaceId: Long): View {
        val composer=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.BOTTOM;setPadding(dp(8),dp(8),dp(8),dp(10));setBackgroundColor(Color.WHITE) }
        val attach=Button(this).apply { text="＋";textSize=22f;contentDescription="إرفاق ملف";setOnClickListener { chooseFile() } };composer.addView(attach,LinearLayout.LayoutParams(dp(54),dp(54)))
        val input=EditText(this).apply { hint="اكتب لنفسك...";minLines=1;maxLines=5;textSize=16f;setPadding(dp(16),dp(10),dp(16),dp(10));background=rounded(0xFFF2F6F5.toInt(),22f) };composer.addView(input,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply { marginStart=dp(6);marginEnd=dp(6) })
        val send=Button(this).apply { text="إرسال";textSize=14f;setTextColor(Color.WHITE);background=rounded(getColor(R.color.masahati_teal),22f);setOnClickListener { val text=input.text.toString().trim();if(text.isNotEmpty()){database.addTextMessage(spaceId,text);input.text.clear();refreshMessages(spaceId)} } };composer.addView(send,LinearLayout.LayoutParams(dp(76),dp(54)))
        return composer
    }

    private fun chooseFile() {
        runCatching {
            openDocument.launch(
                arrayOf(
                    "application/pdf",
                    "image/*",
                    "text/*",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
        }.onFailure { toast("تعذر فتح مدير الملفات") }
    }

    private fun importAttachment(spaceId: Long,uri: Uri) {
        val originalName=queryDisplayName(uri)?:"file";val safeName=MasahatiFormat.safeFileName(originalName);val dir=File(filesDir,"attachments").apply { mkdirs() };val stored=File(dir,"${UUID.randomUUID()}_$safeName")
        try {
            contentResolver.openInputStream(uri).use { input -> requireNotNull(input);stored.outputStream().use { output -> val buffer=ByteArray(DEFAULT_BUFFER_SIZE);var total=0L;while(true){val count=input.read(buffer);if(count<0)break;total+=count;if(total>MAX_ATTACHMENT_BYTES)throw AttachmentTooLargeException();output.write(buffer,0,count)} } }
            database.addFileMessage(spaceId,originalName,stored.absolutePath,contentResolver.getType(uri));refreshMessages(spaceId)
        } catch(_:AttachmentTooLargeException) { stored.delete();toast("الملف أكبر من 50 MB") } catch(_:Exception) { stored.delete();toast("تعذر حفظ الملف") }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching { contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst())c.getString(0)else null } }.getOrNull()

    private fun openAttachment(message: MessageRecord) {
        val path=message.filePath?:return;val file=File(path);if(!file.isFile){toast("الملف غير موجود على الجهاز");return}
        val uri=runCatching { MasahatiFileProvider.uriFor(this,file) }.getOrElse { toast("تعذر فتح الملف");return }
        val intent=Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri,message.mimeType?:"application/octet-stream");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        try { startActivity(Intent.createChooser(intent,"فتح الملف")) } catch(_:Exception) { toast("لا يوجد تطبيق مناسب لفتح هذا الملف") }
    }

    private fun showMessageActions(message: MessageRecord) { AlertDialog.Builder(this).setItems(arrayOf("حذف")) { _,_ -> AlertDialog.Builder(this).setMessage("حذف هذا العنصر؟").setNegativeButton("إلغاء",null).setPositiveButton("حذف") { _,_ -> database.deleteMessage(message);currentSpaceId?.let(::refreshMessages) }.show() }.show() }
    private fun refreshMessages(spaceId: Long) { messageAdapter?.replace(database.listMessages(spaceId));scrollMessagesToBottom() }
    private fun scrollMessagesToBottom() { messageList?.post { val count=messageAdapter?.count?:0;if(count>0)messageList?.setSelection(count-1) } }
    private fun wrapDialogInput(input: EditText): View=FrameLayout(this).apply { setPadding(dp(20),dp(4),dp(20),0);addView(input,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)) }
    private fun rounded(fillColor:Int,radiusDp:Float,strokeColor:Int?=null): GradientDrawable=GradientDrawable().apply { shape=GradientDrawable.RECTANGLE;setColor(fillColor);cornerRadius=dp(radiusDp);if(strokeColor!=null)setStroke(dp(1),strokeColor) }
    private fun dp(value:Int):Int=(value*resources.displayMetrics.density).roundToInt()
    private fun dp(value:Float):Float=value*resources.displayMetrics.density
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_SHORT).show()

    private inner class SpaceAdapter(private var items:List<SpaceSummary>): BaseAdapter() {
        override fun getCount()=items.size;override fun getItem(position:Int)=items[position];override fun getItemId(position:Int)=items[position].id
        fun replace(newItems:List<SpaceSummary>){items=newItems;notifyDataSetChanged()}
        override fun getView(position:Int,convertView:View?,parent:ViewGroup?):View {
            val item=getItem(position);val row=LinearLayout(this@MainActivity).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(11),dp(16),dp(11));background=rounded(Color.WHITE,16f) }
            val avatar=TextView(this@MainActivity).apply { text=item.title.trim().firstOrNull()?.toString()?:"م";textSize=20f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setTypeface(typeface,Typeface.BOLD);background=rounded(getColor(R.color.masahati_teal),18f) };row.addView(avatar,LinearLayout.LayoutParams(dp(54),dp(54)))
            val col=LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),0) };col.addView(TextView(this@MainActivity).apply { text=(if(item.pinned)"📌 "else "")+item.title;textSize=18f;setTextColor(getColor(R.color.masahati_text));setTypeface(typeface,Typeface.BOLD);maxLines=1 });col.addView(TextView(this@MainActivity).apply { text=item.lastPreview?.take(80)?:"لا يوجد محتوى بعد";textSize=14f;setTextColor(0xFF687A77.toInt());maxLines=1 });row.addView(col,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));row.addView(TextView(this@MainActivity).apply { text=MasahatiFormat.listDate(item.lastActivityAt);textSize=12f;setTextColor(0xFF71807E.toInt()) })
            return LinearLayout(this@MainActivity).apply { setPadding(dp(10),dp(4),dp(10),dp(4));addView(row,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }
    }

    private inner class MessageAdapter(private var items:List<MessageRecord>): BaseAdapter() {
        override fun getCount()=items.size;override fun getItem(position:Int)=items[position];override fun getItemId(position:Int)=items[position].id
        fun replace(newItems:List<MessageRecord>){items=newItems;notifyDataSetChanged()}
        override fun getView(position:Int,convertView:View?,parent:ViewGroup?):View {
            val item=getItem(position);val bubble=LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(8));background=rounded(0xFFDDF2EE.toInt(),17f) }
            bubble.addView(TextView(this@MainActivity).apply { text=if(item.type=="file")"📎 ${item.fileName?:"ملف"}\nاضغط لفتح الملف"else item.text.orEmpty();textSize=16f;setTextColor(getColor(R.color.masahati_text));maxWidth=dp(310) });bubble.addView(TextView(this@MainActivity).apply { text=MasahatiFormat.shortTime(item.createdAt);textSize=11f;setTextColor(0xFF60716E.toInt());gravity=Gravity.END;setPadding(0,dp(5),0,0) })
            return FrameLayout(this@MainActivity).apply { setPadding(dp(8),dp(2),dp(8),dp(2));addView(bubble,FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.END)) }
        }
    }

    private class AttachmentTooLargeException: Exception()
    companion object { private const val KEY_SPACE_ID="current_space_id";private const val NO_SPACE=-1L;private const val MAX_ATTACHMENT_BYTES=50L*1024L*1024L }
}
