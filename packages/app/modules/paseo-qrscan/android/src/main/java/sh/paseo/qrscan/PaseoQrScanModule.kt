package sh.paseo.qrscan

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * JS API: NativeModules.PaseoQrScan.scanQr(): Promise<string | null>
 * Resolves with the decoded QR text, or null when the user cancels.
 */
class PaseoQrScanModule(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext), ActivityEventListener {

  companion object {
    private const val REQUEST_CODE = 0x5152 // "QR"
  }

  private var pendingPromise: Promise? = null

  init {
    reactContext.addActivityEventListener(this)
  }

  override fun getName(): String = "PaseoQrScan"

  @ReactMethod
  fun scanQr(promise: Promise) {
    val activity = currentActivity
    if (activity == null) {
      promise.reject("no_activity", "No foreground activity available for scanning")
      return
    }
    if (pendingPromise != null) {
      promise.reject("scan_in_progress", "A QR scan is already in progress")
      return
    }
    pendingPromise = promise
    activity.startActivityForResult(Intent(activity, QrScanActivity::class.java), REQUEST_CODE)
  }

  override fun onActivityResult(
    activity: Activity,
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
  ) {
    if (requestCode != REQUEST_CODE) return
    val promise = pendingPromise ?: return
    pendingPromise = null
    if (resultCode == Activity.RESULT_OK) {
      promise.resolve(data?.getStringExtra(QrScanActivity.RESULT_TEXT))
    } else {
      promise.resolve(null)
    }
  }

  override fun onNewIntent(intent: Intent) = Unit

  override fun invalidate() {
    pendingPromise?.resolve(null)
    pendingPromise = null
    super.invalidate()
  }
}
