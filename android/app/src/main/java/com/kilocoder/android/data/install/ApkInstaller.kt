package com.kilocoder.android.data.install

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

class ApkInstaller(private val context: Context) {
    fun install(file: File): Result<Unit> = runCatching {
        if (!file.isFile) throw IllegalStateException("The downloaded APK is missing.")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
