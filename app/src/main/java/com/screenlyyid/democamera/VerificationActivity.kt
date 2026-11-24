package com.screenlyyid.democamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Step 2 and 3: shows the hosted ScreenlyyID capture page in a WebView and listens for its events.
 *
 * Two things differ from iOS and are the usual reasons capture "does nothing" on Android:
 *  1. The page asks the WebView for the camera, and the WebView only gets it if [WebChromeClient.onPermissionRequest] grants it.
 *     That is not automatic, even when the app already holds the CAMERA permission.
 *  2. Android has no built-in way to receive the page's `postMessage` events, so we register a JavaScript bridge and forward them.
 */
class VerificationActivity : Activity() {

    private lateinit var webView: WebView
    private var verificationId = ""
    private var finished = false
    private var pendingRequest: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verificationId = intent.getStringExtra(EXTRA_VERIFICATION_ID).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Lets the camera preview start playing without a tap.
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), BRIDGE_NAME)
            webViewClient = CaptureClient()
            webChromeClient = CaptureChromeClient()
        }
        setContentView(webView)
        webView.loadUrl(url)
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface(BRIDGE_NAME)
        webView.destroy()
        super.onDestroy()
    }

    // ---- Step 3: events from the hosted page. They look like { "eventName": "", "isError": false, "error": "" } ----

    /** Called from the page's JavaScript, on a background thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            runOnUiThread { handleEvent(json) }
        }
    }

    private fun handleEvent(json: String) {
        if (finished) return
        val event = runCatching { JSONObject(json) }.getOrNull() ?: return
        val isError = event.optBoolean("isError", false)
        when (event.optString("eventName")) {
            "IDV_SESSION_COMPLETE" -> if (!isError) {
                // The document and selfie have been collected. It is safe to close the WebView and carry on.
                finished = true
                // Step 4: the result is read by YOUR back end, which holds the API key and the CorrelationId.
                // The app only asks the back end for the outcome (see ResultActivity).
                startActivity(Intent(this, ResultActivity::class.java).putExtra(EXTRA_VERIFICATION_ID, verificationId))
                finish()
            }
            "ERROR" -> {
                // A fatal, non-recoverable error while processing the images. Restart the flow, or carry on without verification.
                finished = true
                val reason = event.optString("error").ifEmpty { "Verification could not be completed." }
                AlertDialog.Builder(this)
                    .setTitle("Verification failed")
                    .setMessage(reason)
                    .setCancelable(false)
                    .setPositiveButton("Try again") { _, _ -> finish() }
                    .show()
            }
        }
    }

    private inner class CaptureClient : WebViewClient() {
        /** Only ScreenlyyID pages may load in this WebView. Anything else is blocked. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.url.scheme != "https" || !DemoConfig.isScreenlyyidHost(request.url.host)

        override fun onPageFinished(view: WebView, url: String?) {
            if (!DemoConfig.isScreenlyyidHost(android.net.Uri.parse(url ?: "").host)) return
            // Forward the page's postMessage events to the bridge (the Android counterpart of the iOS user script).
            view.evaluateJavascript(
                """
                (function () {
                  if (window.__screenlyyidBridge) return;
                  window.__screenlyyidBridge = true;
                  window.addEventListener('message', function (e) {
                    try { $BRIDGE_NAME.postMessage(JSON.stringify(e.data)); } catch (err) {}
                  });
                })();
                """.trimIndent(),
                null,
            )
        }
    }

    // ---- Camera: the page's request must be granted explicitly ----

    private inner class CaptureChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                // Never hand the camera to a page that is not ScreenlyyID's.
                if (!DemoConfig.isScreenlyyidHost(request.origin.host)) {
                    request.deny()
                    return@runOnUiThread
                }
                val missing = androidPermissionsFor(request).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) {
                    grantAllowed(request)
                } else {
                    // Normally the camera was already granted on the home screen; audio, if the page asks for it, is asked here.
                    pendingRequest?.deny()
                    pendingRequest = request
                    requestPermissions(missing.toTypedArray(), REQUEST_PAGE_PERMISSIONS)
                }
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingRequest == request) pendingRequest = null
        }
    }

    private fun androidPermissionsFor(request: PermissionRequest): List<String> = request.resources.mapNotNull {
        when (it) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
            else -> null
        }
    }

    /** Grants the camera and microphone the app itself holds. Other resources the page may ask for (for example DRM) are not granted. */
    private fun grantAllowed(request: PermissionRequest) {
        val allowed = request.resources.filter {
            (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && hasPermission(Manifest.permission.CAMERA)) ||
                (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && hasPermission(Manifest.permission.RECORD_AUDIO))
        }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    private fun hasPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PAGE_PERMISSIONS) return
        val request = pendingRequest ?: return
        pendingRequest = null
        grantAllowed(request)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_VERIFICATION_ID = "verificationId"
        private const val BRIDGE_NAME = "androidListener"
        private const val REQUEST_PAGE_PERMISSIONS = 2
    }
}
