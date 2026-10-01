package org.tamodak.hisar.data.repository

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Hisar's FileProvider for saved pages. The platform's MIME table doesn't know `.mht`, and
 * WebView renders an archive only when it is told it is one (multipart/related); anything else
 * it treats as a download.
 */
class OfflinePageProvider : FileProvider() {
    override fun getType(uri: Uri): String = "multipart/related"
}
