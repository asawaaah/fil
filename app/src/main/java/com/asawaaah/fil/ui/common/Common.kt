package com.asawaaah.fil.ui.common

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.SubcomposeAsyncImage
import com.asawaaah.fil.AppContainer
import com.asawaaah.fil.FilApp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** ViewModel factory with access to the app container. */
inline fun <reified VM : ViewModel> containerFactory(crossinline create: (AppContainer) -> VM) = viewModelFactory {
    initializer {
        val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FilApp
        create(app.container)
    }
}

fun CreationExtras.container(): AppContainer =
    (this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FilApp).container

object TimeFormat {
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRENCH)
    private val full = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'à' HH'h'mm", Locale.FRENCH)

    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - millis).coerceAtLeast(0)
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> "à l'instant"
            minutes < 60 -> "$minutes min"
            minutes < 24 * 60 -> "${minutes / 60} h"
            else -> {
                val zone = ZoneId.systemDefault()
                val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                val today = LocalDate.now(zone)
                when {
                    date == today.minusDays(1) -> "hier"
                    date.isAfter(today.minusDays(7)) -> "${today.toEpochDay() - date.toEpochDay()} j"
                    date.year == today.year -> dayMonth.format(date)
                    else -> dayMonthYear.format(date)
                }
            }
        }
    }

    private val weekdayDayMonth = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
    private val weekdayDayMonthYear = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)

    /** Day header of the feed: "Aujourd'hui", "Hier", "Lundi 19 octobre". */
    fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when {
        !date.isBefore(today) -> "Aujourd'hui"
        date == today.minusDays(1) -> "Hier"
        date.year == today.year -> weekdayDayMonth.format(date).replaceFirstChar { it.uppercase() }
        else -> weekdayDayMonthYear.format(date).replaceFirstChar { it.uppercase() }
    }

    fun full(millis: Long): String =
        full.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())).replaceFirstChar { it.uppercase() }
}

fun shareLink(context: Context, title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, "$title\n$url")
    }
    context.startActivity(Intent.createChooser(send, "Partager"))
}

fun openInBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

/** Favicon of a source, with a letter as fallback. */
@Composable
fun SourceIcon(iconUrl: String?, title: String, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    val fallback: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.trim().firstOrNull()?.uppercase() ?: "•",
                fontSize = (size.value * 0.55f).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
    if (iconUrl == null) {
        Box(modifier) { fallback() }
    } else {
        SubcomposeAsyncImage(
            model = iconUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4)),
            loading = { fallback() },
            error = { fallback() },
        )
    }
}
