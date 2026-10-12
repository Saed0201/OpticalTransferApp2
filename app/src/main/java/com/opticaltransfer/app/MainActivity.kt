package com.opticaltransfer.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

object CryptoManager {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12

    private fun deriveKey(password: String): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(data: ByteArray, password: String): ByteArray {
        val key = deriveKey(password)
        val iv = ByteArray(IV_LENGTH_BYTE).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        val ciphertext = cipher.doFinal(data)

        return ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    fun decrypt(encryptedPayload: ByteArray, password: String): ByteArray {
        val buffer = ByteBuffer.wrap(encryptedPayload)
        val iv = ByteArray(IV_LENGTH_BYTE)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val key = deriveKey(password)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        return cipher.doFinal(ciphertext)
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var etPassword: EditText
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutSend: View
    private lateinit var layoutReceive: View
    private lateinit var ivQrDisplay: ImageView
    private lateinit var previewView: PreviewView

    private var transmissionJob: Job? = null
    private val qrWriter = QRCodeWriter()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner = BarcodeScanning.getClient()

    private val receivedPackets = mutableMapOf<Int, ByteArray>()
    private var totalExpectedPackets = -1
    private var activeSessionId: String? = null
    private var isDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        toggleMode = findViewById(R.id.toggleMode)
        etPassword = findViewById(R.id.etPassword)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        layoutSend = findViewById(R.id.layoutSend)
        layoutReceive = findViewById(R.id.layoutReceive)
        ivQrDisplay = findViewById(R.id.ivQrDisplay)
        previewView = findViewById(R.id.previewView)

        val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                res.data?.data?.let { uri -> startSending(uri) }
            }
        }

        findViewById<Button>(R.id.btnPickFile).setOnClickListener {
            if (etPassword.text.isNullOrBlank()) {
                Toast.makeText(this, "يرجى كتابة كلمة مرور التشفير أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            filePicker.launch(intent)
        }

        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnModeSend -> switchToSendMode()
                R.id.btnModeReceive -> switchToReceiveMode()
            }
        }
    }

    private fun switchToSendMode() {
        stopCamera()
        layoutReceive.visibility = View.GONE
        layoutSend.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        tvStatus.text = "وضع الإرسال: اختر ملفاً للبدء"
        setScreenBrightness(1.0f)
    }

    private fun switchToReceiveMode() {
        transmissionJob?.cancel()
        layoutSend.visibility = View.GONE
        layoutReceive.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "وضع الاستقبال: وجّه الكاميرا الخلفية نحو شاشة المرسل"
        setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    private fun setScreenBrightness(value: Float) {
        val layout = window.attributes
        layout.screenBrightness = value
        window.attributes = layout
    }

    private fun startSending(uri: Uri) {
        transmissionJob?.cancel()
        transmissionJob = lifecycleScope.launch(Dispatchers.Default) {
            val (fileName, fileBytes) = readFileData(uri)
            val password = etPassword.text.toString()

            withContext(Dispatchers.Main) { tvStatus.text = "جاري تشفير الملف..." }

            val nameBytes = fileName.toByteArray(Charsets.UTF_8)
            val packedStream = ByteArrayOutputStream().apply {
                write(ByteBuffer.allocate(4).putInt(nameBytes.size).array())
                write(nameBytes)
                write(fileBytes)
            }
            val encryptedData = CryptoManager.encrypt(packedStream.toByteArray(), password)

            val chunkSize = 180
            val totalChunks = ceil(encryptedData.size.toDouble() / chunkSize).toInt()
            val fileId = UUID.randomUUID().toString().substring(0, 4)

            withContext(Dispatchers.Main) {
                tvStatus.text = "يبث الآن: $fileName ($totalChunks حزمة)"
            }

            while (isActive) {
                for (index in 0 until totalChunks) {
                    if (!isActive) break
                    val from = index * chunkSize
                    val to = minOf(from + chunkSize, encryptedData.size)
                    val chunk = encryptedData.copyOfRange(from, to)
                    val encodedPayload = Base64.encodeToString(chunk, Base64.NO_WRAP)

                    val packet = "$fileId|$index|$totalChunks|$encodedPayload"
                    val bmp = generateQrBitmap(packet)

                    withContext(Dispatchers.Main) { ivQrDisplay.setImageBitmap(bmp) }
                    delay(90)
                }
            }
        }
    }

    private fun generateQrBitmap(content: String): Bitmap {
        val matrix = qrWriter.encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bmp.setPixel(x, y, if (matrix[x, y]) -0x1000000 else -0x1)
            }
        }
        return bmp
    }

    private fun readFileData(uri: Uri): Pair<String, ByteArray> {
        var name = "file.bin"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx != -1) name = cursor.getString(idx)
        }
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        return Pair(name, bytes)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { img -> processImageProxy(img) }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, analyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (_: Exception) {}
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !isDone) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    for (b in barcodes) b.rawValue?.let { handleScannedData(it) }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    @Synchronized
    private fun handleScannedData(packet: String) {
        val parts = packet.split("|")
        if (parts.size != 4) return

        val sessionId = parts[0]
        val index = parts[1].toIntOrNull() ?: return
        val total = parts[2].toIntOrNull() ?: return
        val rawBase64 = parts[3]

        if (activeSessionId != sessionId) {
            activeSessionId = sessionId
            totalExpectedPackets = total
            receivedPackets.clear()
            isDone = false
            runOnUiThread {
                progressBar.max = total
                progressBar.progress = 0
            }
        }

        if (!receivedPackets.containsKey(index)) {
            receivedPackets[index] = Base64.decode(rawBase64, Base64.NO_WRAP)
            val count = receivedPackets.size

            runOnUiThread {
                progressBar.progress = count
                tvStatus.text = "تم التقاط: $count من أصل $totalExpectedPackets إطار"
            }

            if (count == totalExpectedPackets && !isDone) {
                isDone = true
                runOnUiThread { tvStatus.text = "اكتمل التجميع! جاري فك التشفير..." }
                decryptAndSaveFile()
            }
        }
    }

    private fun decryptAndSaveFile() {
        val password = etPassword.text.toString()
        if (password.isBlank()) {
            runOnUiThread { Toast.makeText(this, "يرجى إدخال كلمة المرور لفك التشفير", Toast.LENGTH_LONG).show() }
            return
        }

        try {
            val stream = ByteArrayOutputStream()
            for (i in 0 until totalExpectedPackets) {
                stream.write(receivedPackets[i]!!)
            }

            val decrypted = CryptoManager.decrypt(stream.toByteArray(), password)
            val buffer = ByteBuffer.wrap(decrypted)
            val nameLength = buffer.int
            val nameBytes = ByteArray(nameLength)
            buffer.get(nameBytes)
            val fileName = String(nameBytes, Charsets.UTF_8)

            val content = ByteArray(buffer.remaining())
            buffer.get(content)

            saveFileToDownloads(fileName, content)
            runOnUiThread {
                tvStatus.text = "تم حفظ الملف بنجاح في التنزيلات:\n$fileName"
                Toast.makeText(this, "اكتمل الاستلام بنجاح!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                tvStatus.text = "فشل فك التشفير: كلمة المرور غير صحيحة"
                isDone = false
            }
        }
    }

    private fun saveFileToDownloads(filename: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let { contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        transmissionJob?.cancel()
        cameraExecutor.shutdown()
    }
}
