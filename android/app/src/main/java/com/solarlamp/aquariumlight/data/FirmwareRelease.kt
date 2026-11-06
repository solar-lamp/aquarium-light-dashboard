package com.solarlamp.aquariumlight.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** The latest controller firmware published by CI (see .github/workflows/firmware.yml). */
object FirmwareRelease {

    private const val VERSION_URL =
        "https://github.com/solar-lamp/aquarium-light-dashboard/releases/download/firmware-latest/version.txt"

    /** e.g. "1.14", or null if it can't be fetched. */
    suspend fun latestVersion(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
            }
            try {
                connection.inputStream.bufferedReader().use { it.readText().trim() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() && it.length < 32 }
    }
}
