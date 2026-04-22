package net.matsudamper.browser

import android.content.Context
import java.io.File

private const val FILE_PROMPTS_CACHE_DIR_NAME = "file_prompts"

internal val Context.filePromptsCacheDir: File
    get() = File(cacheDir, FILE_PROMPTS_CACHE_DIR_NAME)
