package org.tamodak.hisar.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

/**
 * Loads a site's icon off the main thread. Provided at the top of the UI, like an image loader:
 * composables ask for icons by host and show them when they arrive.
 */
fun interface FaviconLoader {
    suspend fun load(host: String?): Bitmap?
}

val LocalFaviconLoader = compositionLocalOf { FaviconLoader { null } }

/** A site's favicon by URL, loaded from the cache without blocking composition. */
@Composable
fun SiteIcon(url: String, label: String, modifier: Modifier = Modifier, size: Dp = 24.dp, icon: Bitmap? = null) {
    val loader = LocalFaviconLoader.current
    val host = url.toUri().host
    val loaded by produceState(icon, host, icon) { if (icon == null) value = loader.load(host) }
    SiteIcon(loaded, label, modifier, size)
}

/** A favicon, or the site's first letter in a circle when there is none (as Chrome draws it). */
@Composable
fun SiteIcon(icon: Bitmap?, label: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    if (icon != null) {
        Image(icon.asImageBitmap(), contentDescription = null, modifier = modifier.size(size).clip(RoundedCornerShape(size / 5)))
    } else {
        Box(
            modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = (size.value * 0.5f).sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Chrome's tab switcher button: the tab count in a rounded square. */
@Composable
fun TabCountIcon(count: Int) {
    val color = LocalContentColor.current
    Box(Modifier.size(20.dp).border(2.dp, color, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
        Text(
            if (count > 99) ":D" else count.toString(),
            color = color,
            fontSize = if (count > 9) 10.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 12.sp,
            modifier = Modifier.padding(bottom = 1.dp),
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        body?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** The host to label a site by, without `www.`. */
fun hostOf(url: String): String = url.toUri().host?.removePrefix("www.") ?: url
