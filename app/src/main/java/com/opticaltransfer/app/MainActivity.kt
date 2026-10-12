package com.opticaltransfer.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
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
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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

    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(pin.trim().toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(6)
    }

    private fun deriveKey(pin: String): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(pin.trim().toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(data: ByteArray, pin: String): ByteArray {
        val key = deriveKey(pin)
        val iv = ByteArray(IV_LENGTH_BYTE).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        val ciphertext = cipher.doFinal(data)

        return ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    fun decrypt(encryptedPayload: ByteArray, pin: String): ByteArray {
        val buffer = ByteBuffer.wrap(encryptedPayload)
        val iv = ByteArray(IV_LENGTH_BYTE)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val key = deriveKey(pin)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        return cipher.doFinal(ciphertext)
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var etPin: EditText
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutSend: View
    private lateinit var layoutReceive: View
    private lateinit var ivQrDisplay: ImageView
    private lateinit var previewView: PreviewView
    private lateinit var btnPickFile: Button
    private lateinit var btnStopSend: Button

    private var transmissionJob: Job? = null
    private val qrWriter = QRCodeWriter()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner = BarcodeScanning.getClient()

    // حالة الاستقبال والنقل
    private val receivedPackets = mutableMapOf<Int, ByteArray>()
    private var totalExpectedPackets = -1
    private var activeSessionId: String? = null
    private var isDone = false

    private var isDialogShowing = false
    private var isTransferApproved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        toggleMode = findViewById(R.id.toggleMode)
        etPin = findViewById(R.id.etPin)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        layoutSend = findViewById(R.id.layoutSend)
        layoutReceive = findViewById(R.id.layoutReceive)
        ivQrDisplay = findViewById(R.id.ivQrDisplay)
        previewView = findViewById(R.id.previewView)
        btnPickFile = findViewById(R.id.btnPickFile)
        btnStopSend = findViewById(R.id.btnStopSend)

        val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                res.data?.data?.let { uri -> startSending(uri) }
            }
        }

        btnPickFile.setOnClickListener {
            if (etPin.text.isNullOrBlank()) {
                Toast.makeText(this, "يرجى كتابة رمز الأمان (PIN) أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            filePicker.launch(intent)
        }

        btnStopSend.setOnClickListener {
            stopSending()
        }

        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnModeSend -> switchToSendMode()
                R.id.btnModeReceive -> switchToReceiveMode()
            }
        }
    }

    private fun triggerVibration(durationMs: Long = 150) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun switchToSendMode() {
        stopCamera()
        layoutReceive.visibility = View.GONE
        layoutSend.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        tvStatus.text = "وضع الإرسال: اختر ملفاً للبدء"
        tvStatus.setTextColor(Color.parseColor("#10B981"))
        setScreenBrightness(1.0f)
    }

    private fun switchToReceiveMode() {
        stopSending()
        resetReceiveState()
        layoutSend.visibility = View.GONE
        layoutReceive.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "بانتظار توجيه الكاميرا ومطابقة الرمز..."
        tvStatus.setTextColor(Color.parseColor("#38BDF8"))
        setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    private fun resetReceiveState() {
        isDialogShowing = false
        isTransferApproved = false
        receivedPackets.clear()
        totalExpectedPackets = -1
        activeSessionId = null
        isDone = false
        progressBar.progress = 0
    }

    private fun setScreenBrightness(value: Float) {
        val layout = window.attributes
        layout.screenBrightness = value
        window.attributes = layout
    }

    private fun startSending(uri: Uri) {
        transmissionJob?.cancel()
        btnStopSend.isEnabled = true
        btnPickFile.isEnabled = false

        transmissionJob = lifecycleScope.launch(Dispatchers.Default) {
            val (fileName, fileBytes) = readFileData(uri)
            val pin = etPin.text.toString()
            val pinHash = CryptoManager.hashPin(pin)

            withContext(Dispatchers.Main) { tvStatus.text = "جاري تشفير الملف..." }

            val nameBytes = fileName.toByteArray(Charsets.UTF_8)
            val packedStream = ByteArrayOutputStream().apply {
                write(ByteBuffer.allocate(4).putInt(nameBytes.size).array())
                write(nameBytes)
                write(fileBytes)
            }
            val encryptedData = CryptoManager.encrypt(packedStream.toByteArray(), pin)

            val chunkSize = 110
            val totalChunks = ceil(encryptedData.size.toDouble() / chunkSize).toInt()
            val fileId = UUID.randomUUID().toString().substring(0, 4)

            withContext(Dispatchers.Main) {
                tvStatus.text = "يبث الآن: $fileName ($totalChunks حزمة)"
            }

            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L
            )

            while (isActive) {
                for (index in 0 until totalChunks) {
                    if (!isActive) break
                    val from = index * chunkSize
                    val to = minOf(from + chunkSize, encryptedData.size)
                    val chunk = encryptedData.copyOfRange(from, to)
                    val encodedPayload = Base64.encodeToString(chunk, Base64.NO_WRAP)

                    val packet = "OPT|$pinHash|$fileId|$index|$totalChunks|$encodedPayload"
                    val bmp = generateQrBitmap(packet, hints)

                    withContext(Dispatchers.Main) { ivQrDisplay.setImageBitmap(bmp) }
                    delay(240)
                }
            }
        }
    }

    private fun stopSending() {
        transmissionJob?.cancel()
        transmissionJob = null
        btnStopSend.isEnabled = false
        btnPickFile.isEnabled = true
        ivQrDisplay.setImageDrawable(null)
        tvStatus.text = "تم إيقاف الإرسال"
        tvStatus.setTextColor(Color.parseColor("#94A3B8"))
    }

    private fun generateQrBitmap(content: String, hints: Map<EncodeHintType, Any>): Bitmap {
        val matrix = qrWriter.encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
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
        if (parts.size != 6 || parts[0] != "OPT") return

        val senderPinHash = parts[1]
        val sessionId = parts[2]
        val index = parts[3].toIntOrNull() ?: return
        val total = parts[4].toIntOrNull() ?: return
        val rawBase64 = parts[5]

        val localPin = etPin.text.toString()
        if (localPin.isBlank()) {
            runOnUiThread {
                tvStatus.text = "⚠️ أدخل رمز الأمان في هاتفك أولاً"
                tvStatus.setTextColor(Color.parseColor("#EF4444"))
            }
            return
        }

        val localPinHash = CryptoManager.hashPin(localPin)

        // 1. حالة عدم تطابق الرمز
        if (senderPinHash != localPinHash) {
            runOnUiThread {
                tvStatus.text = "❌ الرمز غير مطابق! تأكد من إدخال نفس الرمز"
                tvStatus.setTextColor(Color.parseColor("#EF4444"))
                if (!isDialogShowing) {
                    isDialogShowing = true
                    triggerVibration(100)
                    AlertDialog.Builder(this)
                        .setTitle("تنبيه: الرمز غير مطابق ❌")
                        .setMessage("الرمز السري المدخل في هذا الهاتف لا يطابق رمز الهاتف المرسل.\nيرجى تصحيح الرمز للمتابعة.")
                        .setPositiveButton("حسناً") { d, _ ->
                            d.dismiss()
                            isDialogShowing = false
                        }
                        .setCancelable(false)
                        .show()
                }
            }
            return
        }

        // 2. حالة بدء جلسة جديدة
        if (activeSessionId != sessionId) {
            activeSessionId = sessionId
            totalExpectedPackets = total
            receivedPackets.clear()
            isDone = false
            isTransferApproved = false
        }

        // 3. حالة تطابق الرمز: إشعار وسؤال المستخدم (هل يبدأ؟)
        if (!isTransferApproved) {
            if (!isDialogShowing) {
                isDialogShowing = true
                runOnUiThread {
                    triggerVibration(250)
                    tvStatus.text = "✅ الرمز مطابق! بانتظار الموافقة على البدء..."
                    tvStatus.setTextColor(Color.parseColor("#38BDF8"))

                    AlertDialog.Builder(this)
                        .setTitle("تمت مطابقة الرمز بنجاح ✅")
                        .setMessage("تم التحقق من المرسل بنجاح ومطابقة الرمز السري.\nهل تريد بدء استلام الملف الآن؟")
                        .setPositiveButton("نعم (ابدأ)") { d, _ ->
                            d.dismiss()
                            isDialogShowing = false
                            isTransferApproved = true
                            progressBar.max = 100
                            progressBar.progress = 0
                            tvStatus.text = "📥 جاري بدء النقل..."
                            tvStatus.setTextColor(Color.parseColor("#10B981"))
                        }
                        .setNegativeButton("إلغاء") { d, _ ->
                            d.dismiss()
                            isDialogShowing = false
                            resetReceiveState()
                        }
                        .setCancelable(false)
                        .show()
                }
            }
            return
        }

        // 4. بعد الموافقة: بدء تجميع الحزم وحساب نسبة الاستلام %
        if (!receivedPackets.containsKey(index)) {
            receivedPackets[index] = Base64.decode(rawBase64, Base64.NO_WRAP)
            val count = receivedPackets.size
            val percentage = (count * 100) / totalExpectedPackets

            runOnUiThread {
                progressBar.progress = percentage
                tvStatus.text = "📥 نسبة الاستلام: $percentage% ($count من أصل $totalExpectedPackets إطار)"
                tvStatus.setTextColor(Color.parseColor("#10B981"))
            }

            if (count == totalExpectedPackets && !isDone) {
                isDone = true
                runOnUiThread {
                    triggerVibration(300)
                    tvStatus.text = "اكتمل الاستلام 100%! جاري فك التشفير وحفظ الملف..."
                }
                decryptAndSaveFile()
            }
        }
    }

    private fun decryptAndSaveFile() {
        val pin = etPin.text.toString()
        try {
            val stream = ByteArrayOutputStream()
            for (i in 0 until totalExpectedPackets) {
                stream.write(receivedPackets[i]!!)
            }

            val decrypted = CryptoManager.decrypt(stream.toByteArray(), pin)
            val buffer = ByteBuffer.wrap(decrypted)
            val nameLength = buffer.int
            val nameBytes = ByteArray(nameLength)
            buffer.get(nameBytes)
            val fileName = String(nameBytes, Charsets.UTF_8)

            val content = ByteArray(buffer.remaining())
            buffer.get(content)

            saveFileToDownloads(fileName, content)
            runOnUiThread {
                triggerVibration(500)
                tvStatus.text = "🎉 تم الاستلام 100% وحُفظ في التنزيلات:\n$fileName"
                tvStatus.setTextColor(Color.parseColor("#10B981"))

                AlertDialog.Builder(this)
                    .setTitle("اكتمل النقل بنجاح 🎉")
                    .setMessage("تم استلام الملف كاملاً بنسبة 100% وفك تشفيره وحفظه بنجاح:\n$fileName")
                    .setPositiveButton("تم") { d, _ -> d.dismiss() }
                    .show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                tvStatus.text = "فشل فك التشفير: الرمز غير متطابق"
                tvStatus.setTextColor(Color.parseColor("#EF4444"))
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
        stopSending()
        cameraExecutor.shutdown()
    }
}
