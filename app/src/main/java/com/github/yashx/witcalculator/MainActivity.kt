package com.github.yashx.witcalculator

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean


class MainActivity : AppCompatActivity(), Callback<ResponseBody> {

    companion object {
        /**
         * Client Access Token of Wit.ai App
         */
        private const val CLIENT_ACCESS_TOKEN = "your client access token"
        private const val SAMPLE_RATE = 8000
        private const val CHANNEL: Int = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT: Int = AudioFormat.ENCODING_PCM_16BIT
        private val BUFFER_SIZE =
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, AUDIO_FORMAT) * 10
    }

    /**
     * Runnable that read from [recorder] and sends the result to Wit.ai App
     */
    private val streamRunnable = Runnable {
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        val requestBody: RequestBody = object : RequestBody() {
            override fun contentType(): MediaType? {
                return MediaType.parse("audio/raw;encoding=signed-integer;bits=16;rate=8000;endian=little")
            }

            override fun writeTo(bufferedSink: BufferedSink) {
                while (isRecording.get()) {
                    val result = recorder!!.read(buffer, BUFFER_SIZE)
                    if (result < 0) {
                        throw RuntimeException("Reading of audio buffer failed")
                    }
                    bufferedSink.write(buffer)
                    buffer.clear()
                }
            }
        }
        witInterface.forAudioMessage(requestBody).enqueue(this)

    }

    private var isRecording = AtomicBoolean(false)
    private var recorder: AudioRecord? = null

    private lateinit var witInterface: WitInterface

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!checkPermissions()) requestPermissions()

        witInterface = with(Retrofit.Builder()) {
            baseUrl("https://api.wit.ai/")
            with(build()) {
                create(WitInterface::class.java)
            }
        }

        // send Text Message
        sendTextMessageButton.setOnClickListener {
            witInterface.forTextMessage(textMessageInput.text.toString()).enqueue(this)
        }

        // send Audio Message
        sendAudioMessageButton.setOnClickListener {
            if (isRecording.get()) {
                sendAudioMessageButton.text = "Send Audio"
                isRecording.set(false)
                recorder!!.stop()
                recorder!!.release()
                recorder = null
            } else {
                sendAudioMessageButton.text = "Stop Recording"
                isRecording.set(true)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL,
                    AUDIO_FORMAT,
                    BUFFER_SIZE
                )
                recorder!!.startRecording()
                Thread(streamRunnable).start()
            }
        }

    }

    /**
     * Check if [Manifest.permission.INTERNET] and [Manifest.permission.RECORD_AUDIO] is granted
     *
     * @return Boolean stating the result
     */
    private fun checkPermissions(): Boolean {
        val recordAudioResult =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        val internetResult = ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)

        return (recordAudioResult == PackageManager.PERMISSION_GRANTED && internetResult == PackageManager.PERMISSION_GRANTED)
    }

    /**
     * Prompts User for [Manifest.permission.INTERNET] and [Manifest.permission.RECORD_AUDIO] permission
     *
     */
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, arrayOf(
                Manifest.permission.INTERNET,
                Manifest.permission.RECORD_AUDIO
            ), 1000
        )
    }

    interface WitInterface {
        @Headers("Authorization: Bearer $CLIENT_ACCESS_TOKEN")
        @GET("/message")
        fun forTextMessage(
            @Query(value = "q") message: String,
            @Query(value = "v") version: String = "20200513"
        ): Call<ResponseBody>

        @Headers(
            "Authorization: Bearer $CLIENT_ACCESS_TOKEN",
            "Content-Type: audio/raw;encoding=signed-integer;bits=16;rate=8000;endian=little",
            "Transfer-encoding: chunked"
        )
        @POST("/speech")
        fun forAudioMessage(
            @Body body: RequestBody,
            @Query(value = "v") version: String = "20200513"
        ): Call<ResponseBody>
    }

    /**
     * Handles response from Wit.ai App
     *
     */
    override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
        if (response.body() == null) return
        // get the JSON Object sent by Wit.ai
        val data = JSONObject(response.body()!!.string())
        try {
            // get most confident Intent
            val intent = data.getJSONArray("intents").mostConfident() ?: return

            // get most confident wit$number:first Entity
            val number1 = data.getJSONObject("entities")
                .getJSONArray("wit\$number:first").mostConfident()?.get("value")?.toString()
                ?.toDoubleOrNull() ?: return

            // get most confident wit$number:second Entity
            val number2 = data.getJSONObject("entities")
                .getJSONArray("wit\$number:second").mostConfident()?.get("value")?.toString()
                ?.toDoubleOrNull() ?: return

            // based on Intent set text in result TextView
            result.text = when (intent.getString("name")) {
                "add_num" -> number1 + number2
                "sub_num" -> number1 - number2
                "mul_num" -> number1 * number2
                "div_num" -> number1 / number2
                else -> ""
            }.toString()
        } catch (e: Exception) {
            Log.e("OnResponse", "Error getting Entities or Intent", e)
        }
    }

    /**
     * JSONArray Extension function to get most confident object in it
     *
     * @return Most Confident JSONObject
     */
    private fun JSONArray.mostConfident(): JSONObject? {
        var confidentObject: JSONObject? = null
        var maxConfidence = 0.0
        for (i in 0 until length()) {
            try {
                val obj = getJSONObject(i)
                val currConfidence = obj.getDouble("confidence")
                if (currConfidence > maxConfidence) {
                    maxConfidence = currConfidence
                    confidentObject = obj
                }
            } catch (e: JSONException) {
                Log.e("MainActivity", "mostConfident: ", e)
            }
        }
        return confidentObject
    }

    // On failure just log it
    override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
        Log.e("MainActivity", "API call failed")
    }

}