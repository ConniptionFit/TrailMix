package com.trailmix.app.data.obsidian

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.trailmix.app.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class ObsidianExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun exportNote(
        title: String,
        markdown: String,
        createdAtEpochMs: Long,
        durationMs: Long,
    ): String? = withContext(Dispatchers.IO) {
        val vaultUri = settingsRepository.vaultUri.first() ?: return@withContext null
        val folderName = settingsRepository.notesFolder.first()
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(vaultUri))
            ?: return@withContext null

        val folder = tree.findFile(folderName)?.takeIf { it.isDirectory }
            ?: tree.createDirectory(folderName)
            ?: return@withContext null

        val fileName = buildFileName(title, createdAtEpochMs)
        val existing = folder.findFile(fileName)
        val target = existing ?: folder.createFile("text/markdown", fileName)
            ?: return@withContext null

        val body = buildString {
            appendLine("---")
            appendLine("created: ${iso(createdAtEpochMs)}")
            appendLine("source: trailmix")
            appendLine("duration_ms: $durationMs")
            appendLine("---")
            appendLine()
            append(markdown.trim())
            appendLine()
        }

        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
        } ?: return@withContext null

        "$folderName/$fileName"
    }

    fun openInObsidian(relativePath: String, vaultName: String?): Intent? {
        if (vaultName.isNullOrBlank()) return null
        val fileWithoutExt = relativePath.removeSuffix(".md")
        val uri = Uri.parse(
            "obsidian://open?vault=${Uri.encode(vaultName)}&file=${Uri.encode(fileWithoutExt)}",
        )
        return Intent(Intent.ACTION_VIEW, uri)
    }

    private fun buildFileName(title: String, createdAtEpochMs: Long): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(createdAtEpochMs))
        val slug = title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "note" }
            .take(48)
        return "$date-$slug.md"
    }

    private fun iso(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(epochMs))
}
