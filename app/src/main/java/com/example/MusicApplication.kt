package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient

class MusicApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val original = chain.request()
                        val url = original.url.toString()
                        val serverConfig = com.example.data.ServerConfigManager(this)
                        val token = serverConfig.token
                        if (!token.isNullOrEmpty() && (url.contains("/imageproxy") || url.contains("/image") || url.contains("8095"))) {
                            val request = original.newBuilder()
                                .header("Authorization", "Bearer $token")
                                .build()
                            chain.proceed(request)
                        } else {
                            chain.proceed(original)
                        }
                    }
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
