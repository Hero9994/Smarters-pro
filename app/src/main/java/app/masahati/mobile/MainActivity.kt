package app.masahati.mobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val preferences by lazy {
        getSharedPreferences("masahati_foundation", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val launchCount = preferences.getInt(KEY_LAUNCH_COUNT, 0) + 1
        preferences.edit().putInt(KEY_LAUNCH_COUNT, launchCount).apply()

        window.statusBarColor = getColor(R.color.masahati_teal_dark)
        window.navigationBarColor = getColor(R.color.masahati_surface)

        setContentView(buildContent(launchCount))
    }

    private fun buildContent(launchCount: Int): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(getColor(R.color.masahati_surface))
            layoutDirection = LAYOUT_DIRECTION_RTL
        }

        val brand = TextView(this).apply {
            text = getString(R.string.foundation_title)
            textSize = 34f
            setTextColor(getColor(R.color.masahati_teal))
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = getString(R.string.foundation_subtitle)
            textSize = 20f
            setTextColor(getColor(R.color.masahati_text))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }

        val status = TextView(this).apply {
            text = getString(R.string.foundation_status)
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        val body = TextView(this).apply {
            text = getString(R.string.foundation_body)
            textSize = 16f
            setTextColor(getColor(R.color.masahati_text))
            gravity = Gravity.START
            setPadding(dp(18), dp(20), dp(18), dp(20))
            setBackgroundColor(Color.WHITE)
        }

        val launches = TextView(this).apply {
            text = getString(R.string.launch_count_format, launchCount)
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }

        root.addView(brand, matchWrap())
        root.addView(subtitle, matchWrap())
        root.addView(status, matchWrap())
        root.addView(space(dp(28)))
        root.addView(body, matchWrap())
        root.addView(launches, matchWrap())

        return root
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun space(height: Int) = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, height)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val KEY_LAUNCH_COUNT = "launch_count"
    }
}
