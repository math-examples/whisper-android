package com.whispercppdemo

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.whispercppdemo.ui.main.MainScreen
import com.whispercppdemo.ui.main.MainScreenViewModel
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODEL_PATH = "arg1"
        const val EXTRA_SAMPLE_PATH = "arg2"
        const val EXTRA_OUTPUT_PATH = "arg3"
        const val EXTRA_TRANSCRIBE_PROMPT = "arg4"
        const val EXTRA_PRINT_TIMESTAMP = "arg5"
        const val EXTRA_DEBUG_LOG_PATH = "arg6"

        const val EXTRA_PENDING_RESULT_INTENT = "pending_result_intent"
        const val EXTRA_TRANSCRIPTION_RESULT = "extra_result_data"
    }

    private var isLaunchedFromExternalApp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val arg1Value = intent.getStringExtra(EXTRA_MODEL_PATH)
        isLaunchedFromExternalApp = arg1Value != null

        val viewModel: MainScreenViewModel by viewModels {
            MainScreenViewModel.factory(
                modelPath = intent.getStringExtra(EXTRA_MODEL_PATH),
                samplePath = intent.getStringExtra(EXTRA_SAMPLE_PATH),
                outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH),
                transcribePrompt = intent.getStringExtra(EXTRA_TRANSCRIBE_PROMPT),
                printTimestamp = intent.getIntExtra(EXTRA_PRINT_TIMESTAMP, 1) == 1,
                debugLogPath = intent.getStringExtra(EXTRA_DEBUG_LOG_PATH) ?: ""
            )
        }

        viewModel.onTaskFinished = { result ->
            val resultIntent = Intent().apply {
                putExtra(EXTRA_TRANSCRIPTION_RESULT, result)
            }

            if (isLaunchedFromExternalApp) {
                try {
                    val pendingResultIntent = intent.getParcelableExtra<PendingIntent>(EXTRA_PENDING_RESULT_INTENT)
                    if (pendingResultIntent != null) {
                        pendingResultIntent.send(this, Activity.RESULT_OK, resultIntent)
                    } else {
                        setResult(Activity.RESULT_OK, resultIntent)
                    }
                } catch (_: PendingIntent.CanceledException) {
                    setResult(Activity.RESULT_CANCELED)
                } finally {
                    finish()
                }
            }
        }

        setContent {
            WhisperCppDemoTheme {
                MainScreen(viewModel)
            }
        }
    }
}