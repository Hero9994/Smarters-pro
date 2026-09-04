package app.masahati.mobile.ai

import app.masahati.mobile.MessageRow
import org.junit.Assert.assertTrue
import org.junit.Test

class MasahatiLocalPromptTest {
    @Test
    fun focusedDocumentIsExplicitlyIncludedForFollowUpQuestion() {
        val doc = MessageRow(
            id = 44L,
            spaceId = 7L,
            role = "user",
            kind = "file",
            text = "",
            filePath = "/tmp/Scan-20260904.pdf",
            mimeType = "application/pdf",
            displayName = "Scan-20260904-175944.pdf",
            ocrText = "Wohngeldbescheid Bewilligungszeitraum 01.09.2026 bis 31.08.2027",
            classification = "document",
            tags = "Wohngeld، Bescheid",
            summary = "قرار Wohngeld",
            starred = false,
            createdAt = 1L
        )
        val request = MasahatiAiRequest(
            userText = "ما محتوى الورقة وشو سميتها",
            spaceTitle = "وثائق",
            recent = listOf(doc),
            focusedDocument = doc,
            nowIso = "2026-09-04T18:00:00+02:00",
            timezone = "Europe/Berlin"
        )

        val prompt = MasahatiLocalPrompt.build(request)

        assertTrue(prompt.contains("CURRENT_FOCUSED_DOCUMENT"))
        assertTrue(prompt.contains("Scan-20260904-175944.pdf"))
        assertTrue(prompt.contains("Wohngeldbescheid"))
        assertTrue(prompt.contains("ما محتوى الورقة وشو سميتها"))
        assertTrue(prompt.contains("المرجع الأول"))
    }

    @Test
    fun modelCatalogUsesPinnedChecksums() {
        LocalModelCatalog.benchmarkCandidates.forEach { spec ->
            assertTrue(spec.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(spec.downloadUrl.startsWith("https://"))
            assertTrue(spec.expectedBytes > 500_000_000L)
        }
    }

    @Test
    fun compactKeepsBeginningAndEndOfLongDocument() {
        val source = "BEGIN-" + "x".repeat(5000) + "-END"
        val compact = MasahatiLocalPrompt.compact(source, 1000)
        assertTrue(compact.startsWith("BEGIN-"))
        assertTrue(compact.endsWith("-END"))
        assertTrue(compact.length < 1100)
    }

}
