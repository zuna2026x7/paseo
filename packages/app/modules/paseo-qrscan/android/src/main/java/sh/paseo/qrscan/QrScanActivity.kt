package sh.paseo.qrscan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner built on CameraX (AndroidX) + ZXing. No Google
 * Mobile Services or ML Kit involved, so it runs on devices without GMS.
 * Orientation follows the device sensor; frames are rotated per-frame from
 * the camera's own rotation metadata rather than locking the UI.
 */
class QrScanActivity : ComponentActivity() {
  companion object {
    const val RESULT_TEXT = "sh.paseo.qrscan.RESULT_TEXT"
  }

  private val analysisExecutor = Executors.newSingleThreadExecutor()
  private val decoded = AtomicBoolean(false)
  private val reader = MultiFormatReader().apply {
    val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
    hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
    hints[DecodeHintType.TRY_HARDER] = true
    setHints(hints)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val previewView = PreviewView(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      )
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    val frameView = ScanFrameView(this)
    val density = resources.displayMetrics.density
    val closeButton = TextView(this).apply {
      text = "✕"
      setTextColor(Color.WHITE)
      textSize = 22f
      gravity = Gravity.CENTER
      val size = (48 * density).toInt()
      layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
        topMargin = (12 * density).toInt()
        marginStart = (8 * density).toInt()
      }
      setOnClickListener {
        setResult(Activity.RESULT_CANCELED)
        finish()
      }
    }
    val root = FrameLayout(this).apply {
      setBackgroundColor(Color.BLACK)
      addView(previewView)
      addView(frameView)
      addView(closeButton)
    }
    setContentView(root)

    startCamera(previewView)
  }

  override fun onDestroy() {
    analysisExecutor.shutdown()
    super.onDestroy()
  }

  private fun startCamera(previewView: PreviewView) {
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener(
      {
        try {
          val provider = future.get()
          val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
          }
          val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor) { image -> analyze(image) } }
          provider.unbindAll()
          provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        } catch (e: Exception) {
          setResult(Activity.RESULT_CANCELED)
          finish()
        }
      },
      ContextCompat.getMainExecutor(this),
    )
  }

  private fun analyze(image: ImageProxy) {
    try {
      if (decoded.get()) return
      val text = decode(image) ?: return
      if (decoded.compareAndSet(false, true)) {
        val data = Intent().putExtra(RESULT_TEXT, text)
        runOnUiThread {
          setResult(Activity.RESULT_OK, data)
          finish()
        }
      }
    } catch (e: Exception) {
      // A failed frame is not an error; keep scanning subsequent frames.
    } finally {
      image.close()
    }
  }

  private fun decode(image: ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val raw = ByteArray(buffer.remaining())
    buffer.get(raw)
    val width = image.width
    val height = image.height
    val yPlane = if (plane.rowStride == width) {
      raw
    } else {
      val compact = ByteArray(width * height)
      for (row in 0 until height) {
        System.arraycopy(raw, row * plane.rowStride, compact, row * width, width)
      }
      compact
    }
    val (rotated, rotatedWidth, rotatedHeight) =
      rotateY(yPlane, width, height, image.imageInfo.rotationDegrees)
    val source = PlanarYUVLuminanceSource(
      rotated, rotatedWidth, rotatedHeight, 0, 0, rotatedWidth, rotatedHeight, false,
    )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
      reader.decodeWithState(bitmap).text
    } catch (e: Exception) {
      null
    }
  }

  private fun rotateY(data: ByteArray, width: Int, height: Int, degrees: Int): Triple<ByteArray, Int, Int> {
    if (degrees == 0) return Triple(data, width, height)
    val out = ByteArray(data.size)
    when (degrees) {
      90 -> {
        for (y in 0 until height) {
          for (x in 0 until width) {
            out[x * height + (height - 1 - y)] = data[y * width + x]
          }
        }
        return Triple(out, height, width)
      }
      180 -> {
        for (i in data.indices) {
          out[i] = data[data.size - 1 - i]
        }
        return Triple(out, width, height)
      }
      270 -> {
        for (y in 0 until height) {
          for (x in 0 until width) {
            out[(width - 1 - x) * height + y] = data[y * width + x]
          }
        }
        return Triple(out, height, width)
      }
      else -> return Triple(data, width, height)
    }
  }
}

private class ScanFrameView(context: Context) : View(context) {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    style = Paint.Style.STROKE
    strokeWidth = 3f * resources.displayMetrics.density
    strokeCap = Paint.Cap.ROUND
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val size = minOf(width, height) * 0.7f
    val left = (width - size) / 2f
    val top = (height - size) / 2f
    val right = left + size
    val bottom = top + size
    val len = size * 0.12f
    // Top-left
    canvas.drawLine(left, top + len, left, top, paint)
    canvas.drawLine(left, top, left + len, top, paint)
    // Top-right
    canvas.drawLine(right - len, top, right, top, paint)
    canvas.drawLine(right, top, right, top + len, paint)
    // Bottom-left
    canvas.drawLine(left, bottom - len, left, bottom, paint)
    canvas.drawLine(left, bottom, left + len, bottom, paint)
    // Bottom-right
    canvas.drawLine(right, bottom - len, right, bottom, paint)
    canvas.drawLine(right - len, bottom, right, bottom, paint)
  }
}
