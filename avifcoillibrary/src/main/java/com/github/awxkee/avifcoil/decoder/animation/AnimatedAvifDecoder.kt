/*
 * MIT License
 *
 * Copyright (c) 2024 Radzivon Bartoshyk
 * jxl-coder [https://github.com/awxkee/jxl-coder]
 *
 * Created by Radzivon Bartoshyk on 9/3/2024
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.github.awxkee.avifcoil.decoder.animation

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import coil3.Extras
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.bitmapConfig
import coil3.size.Size
import coil3.size.pxOrElse
import com.radzivon.bartoshyk.avif.coder.AvifAnimatedDecoder
import com.radzivon.bartoshyk.avif.coder.PreferredColorConfig
import com.radzivon.bartoshyk.avif.coder.ScaleMode
import kotlinx.coroutines.runInterruptible
import okio.ByteString.Companion.encodeUtf8

public class AnimatedAvifDecoder(
    private val source: SourceFetchResult,
    private val options: Options,
    private val preheatFrames: Int,
    private val exceptionLogger: ((Exception) -> Unit)? = null,
) : Decoder {

    override suspend fun decode(): DecodeResult? = runInterruptible {
        try {
            // ColorSpace is preferred to be ignored due to lib is trying to handle all color profile by itself
            val sourceData = source.source.source().readByteArray()

            var mPreferredColorConfig: PreferredColorConfig = when (options.bitmapConfig) {
                Bitmap.Config.ALPHA_8 -> PreferredColorConfig.RGBA_8888
                Bitmap.Config.RGB_565 -> if (options.allowRgb565) PreferredColorConfig.RGB_565 else PreferredColorConfig.DEFAULT
                Bitmap.Config.ARGB_8888 -> PreferredColorConfig.RGBA_8888
                else -> {
                    if (options.allowHardware) {
                        PreferredColorConfig.DEFAULT
                    } else {
                        PreferredColorConfig.RGBA_8888
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && options.bitmapConfig == Bitmap.Config.RGBA_F16) {
                mPreferredColorConfig = PreferredColorConfig.RGBA_F16
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && options.bitmapConfig == Bitmap.Config.HARDWARE) {
                mPreferredColorConfig = PreferredColorConfig.HARDWARE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && options.bitmapConfig == Bitmap.Config.RGBA_1010102) {
                mPreferredColorConfig = PreferredColorConfig.RGBA_1010102
            }

            if (options.size == Size.ORIGINAL) {
                val originalImage = AvifAnimatedDecoder(sourceData)

                return@runInterruptible DecodeResult(
                    image = originalImage.toCoilImage(
                        colorConfig = mPreferredColorConfig,
                        scaleMode = ScaleMode.FIT,
                    ),
                    isSampled = false
                )
            }


            val originalImage = AvifAnimatedDecoder(sourceData)

            val originalSize = originalImage.getImageSize()
            val (dstWidth, dstHeight) = (originalSize.width to originalSize.height).flexibleResize(
                maxOf(
                    options.size.width.pxOrElse { 0 },
                    options.size.height.pxOrElse { 0 }
                )
            )

            DecodeResult(
                image = originalImage.toCoilImage(
                    dstWidth = dstWidth,
                    dstHeight = dstHeight,
                    colorConfig = mPreferredColorConfig,
                    scaleMode = ScaleMode.FIT
                ),
                isSampled = true
            )
        } catch (e: Exception) {
            exceptionLogger?.invoke(e)
            return@runInterruptible null
        }
    }

    private fun AvifAnimatedDecoder.toCoilImage(
        dstWidth: Int = 0,
        dstHeight: Int = 0,
        colorConfig: PreferredColorConfig,
        scaleMode: ScaleMode
    ): Image = if (getFramesCount() > 1 && options.enableAvifAnimation) {
        AnimatedDrawable(
            frameStore = AvifAnimatedStore(
                avifAnimatedDecoder = this,
                targetWidth = dstWidth,
                targetHeight = dstHeight,
                scaleMode = scaleMode,
                preferredColorConfig = colorConfig
            ),
            preheatFrames = preheatFrames,
            firstFrameAsPlaceholder = true
        )
    } else {
        BitmapDrawable(
            options.context.resources,
            if (dstWidth == 0 || dstHeight == 0) {
                getFrame(
                    frame = 0,
                    preferredColorConfig = colorConfig
                )
            } else {
                getScaledFrame(
                    frame = 0,
                    scaledWidth = dstWidth,
                    scaledHeight = dstHeight,
                    scaleMode = scaleMode,
                    preferredColorConfig = colorConfig
                )
            }
        )
    }.asImage()

    /** Note: If you want to use this decoder in order to convert image into other format, then pass [enableAvifAnimation] with false to [ImageRequest] */
    public class Factory(
        private val preheatFrames: Int = 6,
        private val exceptionLogger: ((Exception) -> Unit)? = null,
    ) : Decoder.Factory {

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            return if (
                AVAILABLE_BRANDS.any {
                    result.source.source().rangeEquals(4, it)
                }
            ) AnimatedAvifDecoder(
                source = result,
                options = options,
                preheatFrames = preheatFrames,
                exceptionLogger = exceptionLogger,
            )
            else null
        }

        companion object {
            private val AVIF = "ftypavif".encodeUtf8()
            private val AVIS = "ftypavis".encodeUtf8()

            private val AVAILABLE_BRANDS = listOf(AVIF, AVIS)
        }
    }

}

private fun Pair<Int, Int>.flexibleResize(
    max: Int
): Pair<Int, Int> {
    val (width, height) = this

    if (max <= 0) return this

    return if (height >= width) {
        val aspectRatio = width.toDouble() / height.toDouble()
        val targetWidth = (max * aspectRatio).toInt()
        targetWidth to max
    } else {
        val aspectRatio = height.toDouble() / width.toDouble()
        val targetHeight = (max * aspectRatio).toInt()
        max to targetHeight
    }
}

/** Note: Only works if you use [AnimatedAvifDecoder] */
fun ImageRequest.Builder.enableAvifAnimation(enableAvifAnimation: Boolean) = apply {
    extras[enableAvifAnimationKey] = enableAvifAnimation
}

/** Note: Only works if you use [AnimatedAvifDecoder] */
val ImageRequest.enableAvifAnimation: Boolean
    get() = getExtra(enableAvifAnimationKey)

/** Note: Only works if you use [AnimatedAvifDecoder] */
val Options.enableAvifAnimation: Boolean
    get() = getExtra(enableAvifAnimationKey)

/** Note: Only works if you use [AnimatedAvifDecoder] */
val Extras.Key.Companion.enableAvifAnimation: Extras.Key<Boolean>
    get() = enableAvifAnimationKey

private val enableAvifAnimationKey = Extras.Key(default = true)