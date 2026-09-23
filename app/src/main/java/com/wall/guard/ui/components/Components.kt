package com.wall.guard.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wall.guard.appmanager.AppInfo
import com.wall.guard.conntrack.Protocol
import com.wall.guard.ui.theme.GuardDanger
import com.wall.guard.ui.theme.GuardAccentGreen
import com.wall.guard.ui.theme.GuardHairline
import com.wall.guard.ui.theme.GuardSurface
import com.wall.guard.ui.theme.GuardSurfaceVariant
import com.wall.guard.ui.theme.GuardTextTertiary
import com.wall.guard.ui.theme.MonoStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun interface AppResolver {
    fun info(uid: Int): AppInfo?
}

@Composable
fun AppIcon(drawable: Drawable?, size: Int = 40) {
    val d = size.dp
    Box(
        modifier = Modifier
            .size(d)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, GuardHairline, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (drawable != null) {
            val bitmap = remember(drawable) {
                try {
                    val w = drawable.intrinsicWidth.coerceIn(1, 256)
                    val h = drawable.intrinsicHeight.coerceIn(1, 256)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    bmp
                } catch (_: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(d),
                    contentScale = ContentScale.Fit
                )
            } else {
                FallbackGlyph(size = d)
            }
        } else {
            FallbackGlyph(size = d)
        }
    }
}

@Composable
private fun FallbackGlyph(size: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.Black.copy(alpha = 0.4f), CircleShape)
    )
}

@Composable
fun ProtocolTag(protocol: Protocol?, blocked: Boolean = false) {
    val (text, color) = when {
        blocked -> "ENGEL" to GuardDanger
        protocol == Protocol.TCP -> "TCP" to androidx.compose.ui.graphics.Color(0xFFE2AE3F)
        protocol == Protocol.UDP -> "UDP" to androidx.compose.ui.graphics.Color(0xFF7FB69B)
        else -> "?" to GuardTextTertiary
    }
    Text(
        text = text,
        style = MonoStyle.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        ),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(GuardSurfaceVariant)
            .border(1.dp, GuardHairline, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(GuardSurface)
            .border(1.dp, GuardHairline, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = value,
            style = MonoStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.US),
        style = MaterialTheme.typography.labelSmall,
        color = GuardTextTertiary,
        modifier = modifier
    )
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    icon: ImageVector = Icons.AutoMirrored.Filled.ShowChart,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun DetailRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value,
            style = MonoStyle.copy(fontSize = 14.sp),
            color = valueColor,
            modifier = Modifier.weight(0.55f)
        )
    }
}

fun formatTime(millis: Long): String {
    return try {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))
    } catch (_: Exception) {
        "--:--:--"
    }
}

fun formatBytesCompact(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes < kb -> "$bytes B"
        bytes < kb * kb -> String.format(Locale.US, "%.1fK", bytes / kb)
        bytes < kb * kb * kb -> String.format(Locale.US, "%.1fM", bytes / (kb * kb))
        else -> String.format(Locale.US, "%.1fG", bytes / (kb * kb * kb))
    }
}

val AccentGreen: Color get() = GuardAccentGreen