package org.tamodak.hisar

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.tamodak.hisar.data.repository.FaviconRepository
import org.tamodak.hisar.data.repository.FilterRepository
import org.tamodak.hisar.ui.browser.BrowserController
import org.tamodak.hisar.ui.browser.BrowserViewModel
import org.tamodak.hisar.ui.browser.SystemLaunchers
import org.tamodak.hisar.ui.components.FaviconLoader
import org.tamodak.hisar.ui.navigation.HisarApp
import org.tamodak.hisar.ui.theme.HisarTheme
import org.tamodak.hisar.web.GeckoEngine
import javax.inject.Inject

/**
 * The single activity. The manifest keeps it across configuration changes so the tabs' pages
 * survive rotation and resizing without reloading.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity(), SystemLaunchers {

    @Inject lateinit var filters: FilterRepository
    @Inject lateinit var favicons: FaviconRepository
    @Inject lateinit var engine: GeckoEngine

    private val viewModel: BrowserViewModel by viewModels()
    private lateinit var controller: BrowserController

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.onFilesPicked(it.resultCode, it.data)
    }
    private var onStoragePermission: ((Boolean) -> Unit)? = null
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onStoragePermission?.invoke(granted)
        onStoragePermission = null
    }
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.onSpeechResult(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
    }
    /** A camera preview is plenty for image search, and needs no camera permission. */
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { controller.onPhoto(it) }
    /** The system photo picker: no storage permission needed. */
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { controller.onPhoto(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash screen stays until the last session's tabs are back, so the first frame is real.
        installSplashScreen().setKeepOnScreenCondition { !viewModel.uiState.value.restored }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        controller = BrowserController(this, viewModel, filters, engine, favicons, this)
        if (savedInstanceState == null) handleIntent(intent)

        // Icons are fetched only from sites the filters would let the page itself load.
        val faviconLoader = FaviconLoader { host, fetch ->
            favicons.load(host) ?: host?.takeIf { fetch && filters.guard().allows("https://$it/") }?.let { favicons.fetch(it) }
        }
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val dark = isSystemInDarkTheme() || settings.simpleMode
            // Status and navigation bar icons follow the theme, simple mode's forced black included.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            HisarTheme(simpleMode = settings.simpleMode, simpleFont = settings.simpleFont) {
                HisarApp(controller, faviconLoader)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Links opened from other apps, and the search widget's three buttons. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?.let(viewModel::openExternalUrl)
            ACTION_SEARCH -> {
                controller.newTab()
                controller.focusOmnibox()
            }
            ACTION_VOICE_SEARCH -> controller.startVoiceSearch(inNewTab = true)
            ACTION_IMAGE_SEARCH -> controller.openImageSearch(inNewTab = true)
        }
    }

    override fun onStart() {
        super.onStart()
        controller.onStart()
    }

    override fun onStop() {
        controller.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }

    // --- SystemLaunchers ---------------------------------------------------------------------------

    override fun pickFiles(intent: Intent) = fileChooser.launch(intent)

    override fun requestStoragePermission(onResult: (Boolean) -> Unit) {
        onStoragePermission = onResult
        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    override fun recognizeSpeech(): Boolean = try {
        speech.launch(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt)),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    override fun takePhoto() {
        try {
            camera.launch(null)
        } catch (_: ActivityNotFoundException) {
            controller.onPhoto(null as android.graphics.Bitmap?)
        }
    }

    override fun pickPhoto() = photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    companion object {
        const val ACTION_SEARCH = "org.tamodak.hisar.action.SEARCH"
        const val ACTION_VOICE_SEARCH = "org.tamodak.hisar.action.VOICE_SEARCH"
        const val ACTION_IMAGE_SEARCH = "org.tamodak.hisar.action.IMAGE_SEARCH"
    }
}
