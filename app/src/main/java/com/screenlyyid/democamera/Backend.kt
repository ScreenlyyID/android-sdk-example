package com.screenlyyid.democamera

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Settings for the demo. */
object DemoConfig {
    /**
     * The address of YOUR back end (the `backend/` folder in this repo). This app never talks to ScreenlyyID directly.
     *
     * The API key and the CorrelationId (the ScreenlyyID session token) live only in the back end. An API key inside an app can be
     * extracted by anyone who has the app, so the ScreenlyyID API must be called from a server you control.
     *
     *  - Emulator: "http://10.0.2.2:3000" (10.0.2.2 is the emulator's name for your computer).
     *  - Real phone over USB: run `adb reverse tcp:3000 tcp:3000`, then use "http://localhost:3000".
     */
    const val BACKEND_URL = "http://10.0.2.2:3000"

    /** The capture page and everything it loads must come from ScreenlyyID. The WebView refuses to navigate anywhere else. */
    fun isScreenlyyidHost(host: String?): Boolean =
        host != null && (host == "screenlyyid.com" || host.endsWith(".screenlyyid.com"))
}

/** What the back end returns from `POST /verifications`. There is no API key and no CorrelationId here: those stay on the server. */
data class VerificationSession(
    /** Your back end's id for this verification. Use it to ask the back end for the result. */
    val verificationId: String,
    /** The hosted document and selfie capture page to open in the WebView. */
    val webEnrolmentUrl: String,
)

/** All the app's network calls. They go to your back end only. Plain `HttpURLConnection`, no libraries. */
object Backend {
    private val executor = Executors.newSingleThreadExecutor()

    /** Step 1: ask the back end to create a verification session. The back end calls ScreenlyyID `instance/create` with the API key. */
    fun createVerification(callback: (Result<VerificationSession>) -> Unit) {
        executor.execute {
            callback(runCatching {
                val json = request("POST", "/verifications", "{}")
                val id = json.optString("verificationId")
                val url = json.optString("webEnrolmentUrl")
                if (id.isEmpty() || url.isEmpty()) throw BackendException("Unexpected response from the back end.")
                VerificationSession(id, url)
            })
        }
    }

    /**
     * Step 4: ask the back end for the result. It answers `{"status":"pending"}` until the result is ready, then
     * `{"status":"ready","result":{...}}`.
     */
    fun fetchResult(verificationId: String, callback: (Result<JSONObject>) -> Unit) {
        executor.execute {
            callback(runCatching { request("GET", "/verifications/$verificationId", null) })
        }
    }

    private fun request(method: String, path: String, body: String?): JSONObject {
        val connection = URL(DemoConfig.BACKEND_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (status in 200..299) return json
            throw BackendException(json.optString("error").ifEmpty { "The back end returned HTTP $status." })
        } finally {
            connection.disconnect()
        }
    }
}

class BackendException(message: String) : Exception(message)
