package com.screenlyyid.democamera

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Step 4: asks the back end for the result once, after IDV_SESSION_COMPLETE, and shows the outcome. */
class ResultActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var heading: TextView
    private var verificationId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verificationId = intent.getStringExtra(VerificationActivity.EXTRA_VERIFICATION_ID).orEmpty()

        heading = TextView(this).apply {
            text = "Checking your result…"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val again = Button(this).apply {
            text = "Scan again"
            setOnClickListener { finish() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(heading)
            addView(content)
            addView(again)
        })
        fetchResult()
    }

    /** The back end read the result from ScreenlyyID once, after IDV_SESSION_COMPLETE. There is no polling. */
    private fun fetchResult() {
        if (verificationId.isEmpty()) return
        Backend.fetchResult(verificationId) { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = { json ->
                        if (json.optString("status") == "ready") show(json.optJSONObject("result") ?: JSONObject())
                        else showMessage("The result is not available yet.")
                    },
                    onFailure = { showMessage("Could not get the result: ${it.message}") },
                )
            }
        }
    }

    /**
     * Shows the decision from `document/result`: `Decision.Level` (HIGH, MEDIUM, LOW), `LivenessResult.Result` (Live or Not Live),
     * `FaceMatchResult.IsMatch`, and the level of each data point in `Verifications`. These fields hold no personal data.
     * In a real app the back end applies this rule and tells the app only the outcome.
     */
    private fun show(result: JSONObject) {
        val level = result.optJSONObject("Decision")?.optString("Level").takeUnless { it.isNullOrEmpty() } ?: "UNKNOWN"
        val liveness = result.optJSONObject("LivenessResult")?.optString("Result").takeUnless { it.isNullOrEmpty() } ?: "Unknown"
        val isMatch = result.optJSONObject("FaceMatchResult")?.optBoolean("IsMatch", false) ?: false
        val isLive = liveness == "Live"

        heading.text = when {
            level == "HIGH" && isLive && isMatch -> "Approved"
            level == "LOW" || !isLive || !isMatch -> "Rejected"
            else -> "Needs review"
        }

        content.removeAllViews()
        addRow("Overall decision", level, bold = true)
        addRow("Liveness", liveness)
        addRow("Face match", if (isMatch) "Matches the ID photo" else "Does not match")

        val names = linkedMapOf(
            "DateOfExpiration" to "Document expiry",
            "DocumentLiveness" to "Document is genuine",
            "FaceVerification" to "Face verification",
            "MrzOcrCrossCheck" to "MRZ cross check",
            "OverallOcrFieldsConfidence" to "Data read from the ID",
            "PassiveLiveness" to "Passive liveness",
        )
        val checks = result.optJSONObject("Verifications")
        if (checks != null) {
            for ((key, label) in names) {
                val itemLevel = checks.optJSONObject(key)?.optString("Level")
                if (!itemLevel.isNullOrEmpty()) addRow(label, itemLevel)
            }
        }
    }

    private fun addRow(name: String, value: String, bold: Boolean = false) {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(this).apply {
            text = name
            textSize = 16f
            setTypeface(null, style)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 16f
            gravity = Gravity.END
            setTypeface(null, style)
        })
        content.addView(row)
    }

    private fun showMessage(message: String) {
        heading.text = "Verification"
        AlertDialog.Builder(this)
            .setTitle("Verification")
            .setMessage(message)
            .setPositiveButton("Try again") { _, _ -> fetchResult() }
            .setNegativeButton("Okay", null)
            .show()
    }
}
