package org.tamodak.hisar.ui.setup

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.tamodak.hisar.R
import org.tamodak.hisar.vision.ModelRepository
import javax.inject.Inject

/** The first start: the picture filter's models are downloaded before the browser opens. */
@HiltViewModel
class ModelSetupViewModel @Inject constructor(private val models: ModelRepository) : ViewModel() {
    val state: StateFlow<ModelRepository.State> = models.state

    init {
        models.start()
    }

    fun retry() = models.retry()
}

/**
 * Shown instead of the browser until the models are on the phone, as games do with their data:
 * what is being downloaded and why, the progress, and a retry button if it fails.
 */
@Composable
fun ModelSetupScreen(state: ModelRepository.State, onRetry: () -> Unit) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(painterResource(R.drawable.ic_hisar_logo), contentDescription = null, Modifier.size(96.dp))
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(stringResource(R.string.setup_text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.setup_privacy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            when (state) {
                ModelRepository.State.Checking, ModelRepository.State.Ready -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.setup_checking), style = MaterialTheme.typography.bodyMedium)
                }
                is ModelRepository.State.Downloading -> {
                    LinearProgressIndicator(
                        progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            R.string.setup_progress,
                            Formatter.formatShortFileSize(context, state.done),
                            Formatter.formatShortFileSize(context, state.total),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.setup_wait),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                ModelRepository.State.Failed -> {
                    Text(
                        stringResource(R.string.setup_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onRetry) { Text(stringResource(R.string.setup_retry)) }
                }
            }
        }
    }
}

@Preview
@Composable
private fun ModelSetupScreenPreview() {
    ModelSetupScreen(ModelRepository.State.Downloading(80_000_000, 227_000_000)) {}
}
