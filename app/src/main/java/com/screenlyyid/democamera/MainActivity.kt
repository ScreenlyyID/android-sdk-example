package com.screenlyyid.democamera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * The home screen: asks for camera permission, then asks the back end for a verification session (step 1) and opens the
 * WebView (step 2) in [VerificationActivity].
 */
class MainActivity : Activity() {

    private lateinit var progress: ProgressBar
    private lateinit var buttons: List<Button>
    private var skipIntro = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "Verify your identity"
            textSize = 24f
        }
        val intro = TextView(this).apply {
            text = "You will be asked to photograph your ID document and take a selfie."
            textSize = 16f
            setPadding(0, 24, 0, 48)
        }
        val start = Button(this).apply {
            text = "Start verification"
            setOnClickListener { begin(skipIntro = false) }
        }
        val startSkip = Button(this).apply {
            text = "Start (skip the intro screen)"
            setOnClickListener { begin(skipIntro = true) }
        }
        buttons = listOf(start, startSkip)
        progress = ProgressBar(this).apply { visibility = View.GONE }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(title)
            addView(intro)
            addView(start)
            addView(startSkip)
            addView(progress)
        })
    }

    override fun onResume() {
        super.onResume()
        setBusy(false)
    }

    private fun begin(skipIntro: Boolean) {
        this.skipIntro = skipIntro
        // Ask for the camera BEFORE opening the capture page, so the user is not asked in the middle of it.
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            createSession()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            createSession()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            // "Don't ask again" was chosen (or the system will not ask): the only way forward is the app's Settings page.
            AlertDialog.Builder(this)
                .setTitle("Camera access needed")
                .setMessage("Verification needs the camera. Turn it on for this app in Settings.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Camera access needed")
                .setMessage("Verification needs the camera to photograph your ID and take a selfie.")
                .setPositiveButton("Try again") { _, _ -> begin(skipIntro) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Step 1: the back end creates the session (it holds the API key). Then step 2: open the hosted page in a WebView. */
    private fun createSession() {
        setBusy(true)
        Backend.createVerification { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = { session ->
                        var url = session.webEnrolmentUrl
                        // skipIntro=true goes straight to image capture.
                        if (skipIntro) url = Uri.parse(url).buildUpon().appendQueryParameter("skipIntro", "true").build().toString()
                        startActivity(Intent(this, VerificationActivity::class.java)
                            .putExtra(VerificationActivity.EXTRA_URL, url)
                            .putExtra(VerificationActivity.EXTRA_VERIFICATION_ID, session.verificationId))
                    },
                    onFailure = { error ->
                        setBusy(false)
                        AlertDialog.Builder(this)
                            .setTitle("Could not start")
                            .setMessage(error.message ?: "Unknown error")
                            .setPositiveButton("Okay", null)
                            .show()
                    },
                )
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        buttons.forEach { it.isEnabled = !busy }
    }

    private companion object {
        const val REQUEST_CAMERA = 1
    }
}
