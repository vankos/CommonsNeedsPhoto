package com.example.commonsneedsphoto.data.upload

/**
 * The simplified licence list used by the original Commons app.
 */
enum class UploadLicense(val template: String) {
    CC_BY_SA_4("{{self|cc-by-sa-4.0}}"),
    CC_BY_4("{{self|cc-by-4.0}}"),
    CC0("{{self|cc-zero}}"),
}
