package com.example.commonsneedsphoto.data.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Network {
    const val USER_AGENT = "CommonsNeedsPhoto/1.0 (Android MVP; https://github.com/vankos)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}
