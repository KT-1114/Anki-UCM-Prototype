package com.kt.ankiucmprototype

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ichi2.anki.api.AddContentApi

class AnkiDroidHelper(context: Context) {
    private val mApi: AddContentApi = AddContentApi(context)
    private val mContext: Context = context.applicationContext

    val api: AddContentApi
        get() = mApi

    fun shouldRequestPermission(): Boolean {
        return ContextCompat.checkSelfPermission(mContext, AddContentApi.READ_WRITE_PERMISSION) != PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission(callbackActivity: Activity, callbackCode: Int) {
        ActivityCompat.requestPermissions(callbackActivity, arrayOf(AddContentApi.READ_WRITE_PERMISSION), callbackCode)
    }

    fun addMedia(uri: Uri, filename: String, mimeType: String): String? {
        return try {
            mApi.addMediaFromUri(uri, filename, mimeType)
        } catch (e: Exception) {
            null
        }
    }
}
