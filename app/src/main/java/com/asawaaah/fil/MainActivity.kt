package com.asawaaah.fil

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import com.asawaaah.fil.data.settings.GlassMode
import com.asawaaah.fil.data.settings.Settings
import com.asawaaah.fil.ui.glass.Accessibility
import com.asawaaah.fil.ui.glass.LocalGlassEnabled
import com.asawaaah.fil.ui.FilNavHost
import com.asawaaah.fil.ui.importopml.ImportViewModel
import com.asawaaah.fil.ui.theme.FilTheme
import com.asawaaah.fil.ui.theme.isDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val container get() = (application as FilApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
            val dark = isDark(settings.theme)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            // Glass bars unless the user prefers opaque ones or Android's high-contrast text is on.
            val glass = settings.glass == GlassMode.LIQUID && !Accessibility.highContrastText(this@MainActivity)
            FilTheme(settings.theme) {
                CompositionLocalProvider(LocalGlassEnabled provides glass) {
                    FilNavHost(container)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** OPML files opened or shared with the app, web links shared to follow a site, notifications. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val articleId = intent.getLongExtra(EXTRA_ARTICLE_ID, -1)
        val feedId = intent.getLongExtra(EXTRA_FEED_ID, -1)
        when {
            articleId > 0 -> container.pendingAction.value = PendingAction.OpenArticle(articleId)
            feedId > 0 -> container.pendingAction.value = PendingAction.OpenFeed(feedId)
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { importFrom(it) }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) {
                    importFrom(stream)
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    val url = Regex("""https?://\S+""").find(text)?.value
                    if (url != null) container.pendingAction.value = PendingAction.AddSource(url)
                }
            }
        }
    }

    companion object {
        const val EXTRA_ARTICLE_ID = "fil.article_id"
        const val EXTRA_FEED_ID = "fil.feed_id"
    }

    private fun importFrom(uri: Uri) {
        val resolver = applicationContext.contentResolver
        container.importManager.load { withContext(Dispatchers.IO) { ImportViewModel.readUri(resolver, uri) } }
        container.pendingAction.value = PendingAction.OpenImport
    }
}
