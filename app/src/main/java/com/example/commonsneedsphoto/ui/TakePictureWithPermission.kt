package com.example.commonsneedsphoto.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract

/**
 * Like ActivityResultContracts.TakePicture, but grants the camera app write
 * access to the FileProvider URI. The stock contract omits these flags, which
 * makes camera apps fail with "exception while saving result to URI".
 */
class TakePictureWithPermission : ActivityResultContract<Uri, Boolean>() {

    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK
}
