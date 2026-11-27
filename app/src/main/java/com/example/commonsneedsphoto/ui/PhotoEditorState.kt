package com.example.commonsneedsphoto.ui

import com.example.commonsneedsphoto.data.photo.AutoGeometry
import com.example.commonsneedsphoto.data.photo.CropRect
import com.example.commonsneedsphoto.data.photo.PerspectiveQuad
import com.example.commonsneedsphoto.data.photo.PhotoEdits
import java.io.File

/**
 * Editor state lives in the ViewModel so an accidental rotation does not throw
 * away the crop or the corrected corners. Bitmaps are not kept here: the
 * dialog derives previews from [source] and these plain values.
 */
data class PhotoEditorUiState(
    val source: File,
    val rotationDegrees: Int = 0,
    val autoGeometry: AutoGeometry? = null,
    val quad: PerspectiveQuad? = null,
    val crop: CropRect = CropRect.FULL,
    /** 0 = off, 1 = full automatic correction. */
    val autoEnhance: Float = 0f,
    val isDetecting: Boolean = false,
    val isSaving: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val edits: PhotoEdits
        get() = PhotoEdits(
            rotationDegrees = rotationDegrees,
            autoGeometry = autoGeometry,
            quad = quad,
            crop = crop,
            autoEnhance = autoEnhance,
        )
}
