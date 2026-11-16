package com.ftlink.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class FtApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }

    // lets Coil show video thumbnails
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
}
