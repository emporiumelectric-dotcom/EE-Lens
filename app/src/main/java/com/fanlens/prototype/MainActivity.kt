package com.fanlens.prototype

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fanlens.prototype.data.EeGraph
import com.fanlens.prototype.recognition.OnDeviceProductRecognitionEngine
import com.fanlens.prototype.ui.ElectricEmporiumScreen
import com.fanlens.prototype.ui.FanLensTheme

class MainActivity : ComponentActivity() {
    private var cameraAllowed by mutableStateOf(false)

    private val repository by lazy { EeGraph.repository(applicationContext) }
    private val recognitionEngine by lazy {
        OnDeviceProductRecognitionEngine(applicationContext, repository)
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraAllowed = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraAllowed = checkSelfPermission(Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        setContent {
            FanLensTheme {
                val products by repository.observeProducts().collectAsState(initial = emptyList())

                ElectricEmporiumScreen(
                    cameraAllowed = cameraAllowed,
                    recognitionEngine = recognitionEngine,
                    repository = repository,
                    products = products,
                    onRequestCamera = { permissionRequest.launch(Manifest.permission.CAMERA) }
                )
            }
        }

        if (!cameraAllowed) permissionRequest.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        recognitionEngine.close()
        super.onDestroy()
    }
}
