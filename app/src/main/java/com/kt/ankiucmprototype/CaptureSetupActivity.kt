package com.kt.ankiucmprototype

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.kt.ankiucmprototype.Constants.ACTION_RUN_OCR
import com.kt.ankiucmprototype.Constants.ACTION_START_CAPTURE
import com.kt.ankiucmprototype.Constants.EXTRA_DATA
import com.kt.ankiucmprototype.Constants.EXTRA_RESULT_CODE

/**
 * A transparent activity required to request system-level screen capture permissions.
 *
 * This activity acts as a bridge to obtain [MediaProjection] tokens from the user. Since the 
 * MediaProjection API requires an activity context to launch the permission dialog, this 
 * component handles the request and pipes the resulting token back to the [UcmOverlayService].
 */
class CaptureSetupActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, UcmOverlayService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, result.resultCode)
                putExtra(EXTRA_DATA, result.data)
            }
            startForegroundService(intent)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (UcmOverlayService.isServiceRunning && UcmOverlayService.hasProjection) {
            Log.d("CaptureSetupActivity", "Service is already running with projection, triggering OCR")
            val intent = Intent(this, UcmOverlayService::class.java).apply {
                action = ACTION_RUN_OCR
            }
            startService(intent)
            finish()
            return
        }

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            mediaProjectionManager.createScreenCaptureIntent()
        }

        projectionLauncher.launch(captureIntent)
    }
}
