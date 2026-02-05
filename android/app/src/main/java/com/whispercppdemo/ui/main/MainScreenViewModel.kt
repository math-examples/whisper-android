package com.whispercppdemo.ui.main

import android.app.Application
import android.media.MediaPlayer
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercpp.whisper.WhisperContext
import com.whispercppdemo.media.decodeWaveFile
import com.whispercppdemo.recorder.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LOG_TAG = "MainScreenViewModel"

class MainScreenViewModel(
    private val application: Application,
    private val externalModelPath: String?,
    private val externalSamplePath: String?,
    private val outputPath: String?,
    private val transcribePrompt: String?,
    private val printTimestamp: Boolean,
    private val debugLogPath: String
) : ViewModel() {
    var canTranscribe by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set
    var isRecording by mutableStateOf(false)
        private set

    var onTaskFinished: ((String) -> Unit)? = null

    private val sdCardRoot = Environment.getExternalStorageDirectory()
    private val recorder = Recorder()
    private var recordedFile: File? = null
    private var whisperContext: WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null

    private val debugLogFile: File? =
        debugLogPath.takeIf { it.isNotEmpty() }?.let { File(it) }

    init {
        viewModelScope.launch {
            initDebugLog()
            logArgs()
            printSystemInfo()
            loadModel()

            if (!externalSamplePath.isNullOrEmpty()) {
                transcribeSample()
            }
        }
    }

    private suspend fun writeLog(msg: String) {
        withContext(Dispatchers.IO) {
            debugLogFile?.let {
                FileWriter(it, true).use { w ->
                    w.write("[${timestamp()}] $msg\n")
                }
            }
            withContext(Dispatchers.Main) {
                dataLog += "$msg\n"
            }
        }
    }

    private suspend fun initDebugLog() {
        debugLogFile ?: return
        debugLogFile.parentFile?.mkdirs()
        FileWriter(debugLogFile, false).use { it.write("") }
        writeLog("Debug logging enabled: ${debugLogFile.absolutePath}")
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private suspend fun logArgs() {
        writeLog("Model Path: $externalModelPath")
        writeLog("Sample Path: $externalSamplePath")
        writeLog("Output Path: $outputPath")
        writeLog("Prompt: $transcribePrompt")
        writeLog("Print Timestamp: $printTimestamp")
    }

    private suspend fun printSystemInfo() {
        writeLog("System Info: ${WhisperContext.getSystemInfo()}")
    }

    private suspend fun loadModel() {
        writeLog("Loading model...")
        whisperContext = withContext(Dispatchers.IO) {
            externalModelPath?.let {
                val externalModelFile = File(it)
                if (externalModelFile.exists()) {
                    WhisperContext.createContextFromFile(externalModelFile.absolutePath)
                } else {
                    writeLog("Model not found at provided path: $externalModelPath. Falling back to SD card.")
                    val sdCardModelDir = File(sdCardRoot, "whisper/models")
                    val modelFile = sdCardModelDir.listFiles()?.firstOrNull()
                        ?: throw Exception("No model found on SD card")
                    WhisperContext.createContextFromFile(modelFile.absolutePath)
                }
            } ?: run {
                writeLog("No external model path provided. Loading default model from SD card.")
                val sdCardModelDir = File(sdCardRoot, "whisper/models")
                val modelFile = sdCardModelDir.listFiles()?.firstOrNull()
                    ?: throw Exception("No model found on SD card")
                WhisperContext.createContextFromFile(modelFile.absolutePath)
            }
        }
        canTranscribe = true
        writeLog("Model loaded successfully.")
    }

    fun benchmark() = viewModelScope.launch {
        if (!canTranscribe) return@launch
        canTranscribe = false
        writeLog("Benchmark started")

        val benchMemoryResult = whisperContext?.benchMemory(4)
        benchMemoryResult?.let { writeLog(it.toString()) } ?: writeLog("Error: benchMemory() returned null")

        val benchGgmlMulMatResult = whisperContext?.benchGgmlMulMat(4)
        benchGgmlMulMatResult?.let { writeLog(it.toString()) } ?: writeLog("Error: benchGgmlMulMat() returned null")

        writeLog("Benchmark finished")
        canTranscribe = true
    }
    
    fun transcribeSample() = viewModelScope.launch {
        transcribeAudio(getFirstSample())
    }

    private suspend fun getFirstSample(): File = withContext(Dispatchers.IO) {
        if (!externalSamplePath.isNullOrEmpty()) {
            val externalSampleFile = File(externalSamplePath)
            if (externalSampleFile.exists()) {
                writeLog("Using sample from intent path: ${externalSampleFile.name}")
                return@withContext externalSampleFile
            } else {
                writeLog("Intent sample path not found: $externalSamplePath. No fallback to SD card.")
                throw Exception("Sample file not found at provided path: $externalSamplePath")
            }
        } else {
            writeLog("No external sample path provided. Falling back to SD card.")
            val sdCardSampleDir = File(sdCardRoot, "whisper/samples")
            val sampleFile = sdCardSampleDir.listFiles()?.firstOrNull()
                ?: throw Exception("No sample found on SD card")

            writeLog("Using sample from SD card: ${sampleFile.name}")
            return@withContext sampleFile
        }
    }
    
    fun toggleRecord() = viewModelScope.launch {
        if (isRecording) {
            recorder.stopRecording()
            isRecording = false
            recordedFile?.let { transcribeAudio(it) }
        } else {
            recordedFile = File.createTempFile("recording", ".wav")

            recorder.startRecording(recordedFile!!) { e ->
                viewModelScope.launch {
                    writeLog("RECORDING ERROR: ${e.localizedMessage}")
                    isRecording = false
                    onTaskFinished?.invoke("ERROR: ${e.localizedMessage}")
                }
            }

            isRecording = true
            writeLog("Recording started: ${recordedFile!!.absolutePath}")
        }
    }

    private suspend fun transcribeAudio(file: File) {
        canTranscribe = false
        try {
            writeLog("Reading samples...")
            val data = readAudioSamples(file)
            writeLog("Transcribing...")

            val result = whisperContext?.transcribeData(data, printTimestamp, transcribePrompt) ?: "No transcription result"
           
            if (printTimestamp) writeLog("Transcription timestamp: ${timestamp()}")
            
            saveResult(result)
            writeLog("Done: $result")
            onTaskFinished?.invoke(result)

        } catch (e: Exception) {
            val errorMsg = "ERROR: ${e.localizedMessage}"
            writeLog(errorMsg)
            onTaskFinished?.invoke(errorMsg)
        } finally {
            canTranscribe = true
        }
    }

    private suspend fun saveResult(text: String) = withContext(Dispatchers.IO) {
        val file = outputPath?.let { File(it) }
            ?: File(sdCardRoot, "whisper/output.txt")
        file.parentFile?.mkdirs()
        FileWriter(file).use { it.write(text) }
    }

    private suspend fun readAudioSamples(file: File): FloatArray = withContext(Dispatchers.IO) {
        stopPlayback()
        startPlayback(file)
        decodeWaveFile(file)
    }

    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        mediaPlayer = MediaPlayer.create(application, file.absolutePath.toUri())
        mediaPlayer?.start()
    }

    override fun onCleared() {
        viewModelScope.launch {
            whisperContext?.release()
            stopPlayback()
            writeLog("ViewModel cleared")
        }
    }

    companion object {
        private var factoryModelPath: String? = null
        private var factorySamplePath: String? = null
        private var factoryOutputPath: String? = null
        private var factoryTranscribePrompt: String? = null
        private var factoryPrintTimestamp: Boolean = true
        private var factoryDebugLogPath: String = ""

        fun factory(
            modelPath: String? = null,
            samplePath: String? = null,
            outputPath: String? = null,
            transcribePrompt: String? = null,
            printTimestamp: Boolean = true,
            debugLogPath: String = ""
        ) = viewModelFactory {
            factoryModelPath = modelPath
            factorySamplePath = samplePath
            factoryOutputPath = outputPath
            factoryTranscribePrompt = transcribePrompt
            factoryPrintTimestamp = printTimestamp
            factoryDebugLogPath = debugLogPath

            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(
                    application = application,
                    externalModelPath = factoryModelPath,
                    externalSamplePath = factorySamplePath,
                    outputPath = factoryOutputPath,
                    transcribePrompt = factoryTranscribePrompt,
                    printTimestamp = factoryPrintTimestamp,
                    debugLogPath = factoryDebugLogPath
                )
            }
        }
    }
}