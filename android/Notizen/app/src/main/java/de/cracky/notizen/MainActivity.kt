package de.cracky.notizen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import de.cracky.notizen.ui.NotizenApp

class MainActivity : ComponentActivity() {
    private val notesViewModel: NotesViewModel by viewModels()
    private var createdAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        createdAt = android.os.SystemClock.uptimeMillis()
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition {
            !notesViewModel.state.value.initialized && android.os.SystemClock.uptimeMillis() - createdAt < 700L
        }
        handleIncomingIntent(intent)
        setContent { NotizenApp(notesViewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        notesViewModel.onForeground()
    }

    override fun onPause() {
        notesViewModel.onBackground()
        super.onPause()
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        if (incoming.action == Intent.ACTION_VIEW) {
            incoming.data?.toString()?.takeIf { it.contains("magic=") }?.let(notesViewModel::loginWithMagicLink)
            return
        }
        if (incoming.action != Intent.ACTION_SEND && incoming.action != Intent.ACTION_SEND_MULTIPLE) return
        val text = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val title = incoming.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val uris = linkedSetOf<Uri>()
        incoming.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        if (Build.VERSION.SDK_INT >= 33) {
            if (incoming.action == Intent.ACTION_SEND) incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
            else incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.forEach { uris.add(it) }
        } else {
            @Suppress("DEPRECATION")
            if (incoming.action == Intent.ACTION_SEND) (incoming.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uris.add(it) }
            else {
                @Suppress("DEPRECATION")
                incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uris.add(it) }
            }
        }
        if (text.isNotBlank() || uris.isNotEmpty()) notesViewModel.handleShare(title, text, uris.toList())
    }

}
