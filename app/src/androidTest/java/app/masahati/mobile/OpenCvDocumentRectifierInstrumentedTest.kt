package app.masahati.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenCvDocumentRectifierInstrumentedTest {
    @Test
    fun perspectiveDocumentIsDetectedAndRectified() {
        assertTrue(OpenCvDocumentRectifier.isAvailable())

        val source = Bitmap.createBitmap(1000, 1300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.rgb(80, 88, 82))

        val page = Path().apply {
            moveTo(150f, 120f)
            lineTo(855f, 175f)
            lineTo(925f, 1160f)
            lineTo(95f, 1090f)
            close()
        }
        canvas.drawPath(page, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        })
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 45, 45)
            strokeWidth = 8f
        }
        for (y in 320..900 step 90) {
            canvas.drawLine(250f, y.toFloat(), 760f, (y + 18).toFloat(), ink)
        }

        val result = OpenCvDocumentRectifier.rectifyIfHelpful(source)
        try {
            assertNotSame(source, result)
            assertTrue(result.width >= 650)
            assertTrue(result.height >= 850)
            val ratio = result.width.toDouble() / result.height.toDouble()
            assertTrue(ratio in 0.55..0.90)
        } finally {
            if (result !== source) result.recycle()
            source.recycle()
        }
    }
}
