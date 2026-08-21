package com.drivool.iot.powertap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_BLE_ADDRESS = "extra_ble_address"
        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        private const val REQUEST_CAMERA = 1001
    }

    private lateinit var previewView: PreviewView
    private lateinit var txtHint: TextView
    private lateinit var connectProgress: ProgressBar
    private lateinit var btnCancel: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val handled = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)

        previewView = findViewById(R.id.previewView)
        txtHint = findViewById(R.id.txtHint)
        connectProgress = findViewById(R.id.connectProgress)
        btnCancel = findViewById(R.id.btnCancel)

        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            bindCameraUseCases(provider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(options)

        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processFrame(scanner, imageProxy)
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processFrame(
        scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
        imageProxy: androidx.camera.core.ImageProxy,
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || handled.get()) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull()?.rawValue ?: return@addOnSuccessListener
                onQrDetected(value)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun onQrDetected(raw: String) {
        if (!handled.compareAndSet(false, true)) return

        val qr = PowerTapQr.parse(raw)
        if (qr == null) {
            handled.set(false)
            runOnUiThread {
                Toast.makeText(this, "Invalid PowerTap QR code", Toast.LENGTH_SHORT).show()
            }
            return
        }

        runOnUiThread {
            txtHint.text = "Connecting to ${qr.displayName}…"
            connectProgress.visibility = View.VISIBLE
            cameraProvider?.unbindAll()

            val connected = GatewayManager.connectFromQr(qr)
            if (!connected) {
                Toast.makeText(this, "Turn on Bluetooth to connect", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
                return@runOnUiThread
            }

            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra(EXTRA_DEVICE_ID, qr.deviceId)
                    putExtra(EXTRA_BLE_ADDRESS, qr.bleAddress)
                    putExtra(EXTRA_DISPLAY_NAME, qr.displayName)
                },
            )
            Toast.makeText(this, "Connecting to ${qr.displayName}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
