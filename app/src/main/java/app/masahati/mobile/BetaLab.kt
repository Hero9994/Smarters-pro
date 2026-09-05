package app.masahati.mobile

import android.content.Context
import androidx.core.content.edit
import app.masahati.mobile.ai.LocalModelCatalog
import app.masahati.mobile.ai.LocalModelSpec

enum class BetaAiStrategy(val label: String, val apiValue: String) {
    AUTO_HYBRID("تلقائي ذكي: سحابي ثم محلي", "auto"),
    CLOUD_QUALITY("سحابي جودة أعلى", "quality"),
    CLOUD_FAST("سحابي سريع", "fast"),
    LOCAL_MODEL_ONLY("النموذج المحلي فقط", "local"),
    RULES_ONLY("قواعد محلية فقط", "rules")
}

enum class BetaScanMode(val label: String) {
    BALANCED("تنظيف متوازن"),
    STRONG("إزالة ظلال قوية"),
    BLACK_WHITE("أبيض وأسود للمستندات"),
    ORIGINAL("بدون تنظيف إضافي")
}

class BetaLab(context: Context) {
    private val prefs = context.getSharedPreferences("masahati_beta_lab", Context.MODE_PRIVATE)

    fun aiStrategy(): BetaAiStrategy = runCatching {
        BetaAiStrategy.valueOf(prefs.getString(KEY_AI, BetaAiStrategy.AUTO_HYBRID.name)!!)
    }.getOrDefault(BetaAiStrategy.AUTO_HYBRID)

    fun setAiStrategy(value: BetaAiStrategy) {
        prefs.edit { putString(KEY_AI, value.name) }
    }

    fun scanMode(): BetaScanMode = runCatching {
        BetaScanMode.valueOf(prefs.getString(KEY_SCAN, BetaScanMode.BALANCED.name)!!)
    }.getOrDefault(BetaScanMode.BALANCED)

    fun setScanMode(value: BetaScanMode) {
        prefs.edit { putString(KEY_SCAN, value.name) }
    }

    fun localModelSpec(): LocalModelSpec =
        if (prefs.getString(KEY_MODEL, "qwen") == "gemma") LocalModelCatalog.GEMMA4_E2B
        else LocalModelCatalog.QWEN3_1_7B_DYNAMIC

    fun setLocalModel(id: String) {
        prefs.edit { putString(KEY_MODEL, if (id == "gemma") "gemma" else "qwen") }
    }

    fun showEngineLabels(): Boolean = prefs.getBoolean(KEY_ENGINE_LABELS, true)

    fun setShowEngineLabels(value: Boolean) {
        prefs.edit { putBoolean(KEY_ENGINE_LABELS, value) }
    }

    fun summary(): String =
        "الذكاء: ${aiStrategy().label}\n" +
        "النموذج المحلي: ${localModelSpec().displayName}\n" +
        "السكانر: ${scanMode().label}\n" +
        "إظهار مصدر الرد: ${if (showEngineLabels()) "نعم" else "لا"}"

    companion object {
        private const val KEY_AI = "ai_strategy"
        private const val KEY_SCAN = "scan_mode"
        private const val KEY_MODEL = "local_model"
        private const val KEY_ENGINE_LABELS = "show_engine_labels"
    }
}
