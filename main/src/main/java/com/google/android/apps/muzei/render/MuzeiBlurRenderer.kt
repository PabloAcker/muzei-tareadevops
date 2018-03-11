/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.render

import android.app.ActivityManager
import android.arch.lifecycle.MutableLiveData
import android.content.Context
import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.support.annotation.Keep
import android.util.Log
import android.view.animation.AccelerateDecelerateInterpolator
import com.google.android.apps.muzei.ArtDetailViewport
import com.google.android.apps.muzei.event.SwitchingPhotosStateChangedEvent
import com.google.android.apps.muzei.settings.Prefs
import com.google.android.apps.muzei.util.*
import org.greenrobot.eventbus.EventBus
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class ArtworkSize(val width: Int, val height: Int) {
    internal constructor(bitmapRegionLoader: BitmapRegionLoader)
            : this(bitmapRegionLoader.width, bitmapRegionLoader
            .height)
}

object ArtworkSizeLiveData : MutableLiveData<ArtworkSize>()

class MuzeiBlurRenderer(private val context: Context,
                         private val callbacks: Callbacks,
                         private val demoMode: Boolean = false,
                         private val preview: Boolean = false)
    : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "MuzeiBlurRenderer"

        private const val CROSSFADE_ANIMATION_DURATION = 750
        private const val BLUR_ANIMATION_DURATION = 750

        const val DEFAULT_BLUR = 250 // max 500
        const val DEFAULT_GREY = 0 // max 500
        const val DEFAULT_MAX_DIM = 128 // technical max 255
        private const val DEMO_BLUR = 250
        private const val DEMO_DIM = 64
        private const val DEMO_GREY = 0
        private const val DIM_RANGE = 0.5f // percent of max dim
    }

    private val blurKeyframes: Int
    private var maxPrescaledBlurPixels: Int = 0
    private var blurredSampleSize: Int = 0
    private var maxDim: Int = 0
    private var maxGrey: Int = 0

    // Model and view matrices. Projection and MVP stored in picture set
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    private var aspectRatio: Float = 0f
    private var currentHeight: Int = 0

    private var currentGLPictureSet: GLPictureSet
    private var nextGLPictureSet: GLPictureSet
    private lateinit var colorOverlay: GLColorOverlay

    private var queuedNextBitmapRegionLoader: BitmapRegionLoader? = null

    private var surfaceCreated: Boolean = false

    @Volatile
    private var normalOffsetX: Float = 0f
    private val currentViewport = RectF() // [-1, -1] to [1, 1], flipped

    var isBlurred = true
        private set
    private var blurRelatedToArtDetailMode = false
    private val blurInterpolator = AccelerateDecelerateInterpolator()
    private val blurAnimator = TickingFloatAnimator(BLUR_ANIMATION_DURATION * if (demoMode) 5 else 1)
    private val crossfadeAnimator = TickingFloatAnimator(CROSSFADE_ANIMATION_DURATION)

    init {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        blurKeyframes = if (activityManager.isLowRamDevice) 1 else 2
        blurAnimator.currentValue = blurKeyframes.toFloat()

        currentGLPictureSet = GLPictureSet(0)
        nextGLPictureSet = GLPictureSet(1) // for transitioning to next pictures
        setNormalOffsetX(0f)
        recomputeMaxPrescaledBlurPixels()
        recomputeMaxDimAmount()
        recomputeGreyAmount()
    }

    fun recomputeMaxPrescaledBlurPixels() {
        // Compute blur sizes
        val blurAmount = if (demoMode)
            DEMO_BLUR
        else
            Prefs.getSharedPreferences(context)
                    .getInt(Prefs.PREF_BLUR_AMOUNT, DEFAULT_BLUR)
        val maxBlurRadiusOverScreenHeight = blurAmount * 0.0001f
        val dm = context.resources.displayMetrics
        val maxBlurPx = (dm.heightPixels * maxBlurRadiusOverScreenHeight).toInt()
        blurredSampleSize = 4
        while (maxBlurPx / blurredSampleSize > ImageBlurrer.MAX_SUPPORTED_BLUR_PIXELS) {
            blurredSampleSize = blurredSampleSize shl 1
        }
        maxPrescaledBlurPixels = maxBlurPx / blurredSampleSize
    }

    fun recomputeMaxDimAmount() {
        maxDim = Prefs.getSharedPreferences(context).getInt(
                Prefs.PREF_DIM_AMOUNT, DEFAULT_MAX_DIM)
    }

    fun recomputeGreyAmount() {
        maxGrey = if (demoMode)
            DEMO_GREY
        else
            Prefs.getSharedPreferences(context)
                    .getInt(Prefs.PREF_GREY_AMOUNT, DEFAULT_GREY)
    }

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        surfaceCreated = false
        GLES20.glEnable(GLES20.GL_BLEND)
        //        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glClearColor(0f, 0f, 0f, 0f)

        // Set the camera position (View matrix)
        Matrix.setLookAtM(viewMatrix, 0,
                0f, 0f, 1f,
                0f, 0f, -1f,
                0f, 1f, 0f)

        GLColorOverlay.initGl()
        GLPicture.initGl()

        colorOverlay = GLColorOverlay()

        surfaceCreated = true
        val loader = queuedNextBitmapRegionLoader
        if (loader != null) {
            setAndConsumeBitmapRegionLoader(loader)
            queuedNextBitmapRegionLoader = null
        }
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        hintViewportSize(width, height)
        if (!demoMode && !preview) {
            // Reset art detail viewports
            ArtDetailViewport.setViewport(0, 0f, 0f, 0f, 0f)
            ArtDetailViewport.setViewport(1, 0f, 0f, 0f, 0f)
        }
        currentGLPictureSet.recomputeTransformMatrices()
        nextGLPictureSet.recomputeTransformMatrices()
        recomputeMaxPrescaledBlurPixels()
    }

    fun hintViewportSize(width: Int, height: Int) {
        currentHeight = height
        aspectRatio = width * 1f / height
    }

    override fun onDrawFrame(unused: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        Matrix.setIdentityM(modelMatrix, 0)

        val stillAnimating = crossfadeAnimator.tick() or blurAnimator.tick()

        if (blurRelatedToArtDetailMode) {
            currentGLPictureSet.recomputeTransformMatrices()
            nextGLPictureSet.recomputeTransformMatrices()
        }

        var dimAmount = currentGLPictureSet.dimAmount.toFloat()
        currentGLPictureSet.drawFrame(1f)
        if (crossfadeAnimator.isRunning) {
            dimAmount = interpolate(dimAmount, nextGLPictureSet.dimAmount.toFloat(),
                    crossfadeAnimator.currentValue)
            nextGLPictureSet.drawFrame(crossfadeAnimator.currentValue)
        }

        colorOverlay.color = Color.argb((dimAmount * blurAnimator.currentValue / blurKeyframes).toInt(), 0, 0, 0)
        colorOverlay.draw(modelMatrix) // don't need any perspective or anything for color overlay

        if (stillAnimating) {
            callbacks.requestRender()
        }
    }

    @Keep
    fun setNormalOffsetX(x: Float) {
        normalOffsetX = x.constrain(0f, 1f)
        onViewportChanged()
    }

    private fun onViewportChanged() {
        currentGLPictureSet.recomputeTransformMatrices()
        nextGLPictureSet.recomputeTransformMatrices()
        if (surfaceCreated) {
            callbacks.requestRender()
        }
    }

    private fun blurRadiusAtFrame(f: Float): Float {
        return maxPrescaledBlurPixels * blurInterpolator.getInterpolation(f / blurKeyframes)
    }

    fun setAndConsumeBitmapRegionLoader(bitmapRegionLoader: BitmapRegionLoader) {
        if (!surfaceCreated) {
            queuedNextBitmapRegionLoader = bitmapRegionLoader
            return
        }

        if (crossfadeAnimator.isRunning) {
            queuedNextBitmapRegionLoader?.destroy()
            queuedNextBitmapRegionLoader = bitmapRegionLoader
            return
        }

        if (!demoMode && !preview) {
            EventBus.getDefault().postSticky(SwitchingPhotosStateChangedEvent(
                    nextGLPictureSet.id, true))
            ArtworkSizeLiveData.postValue(ArtworkSize(bitmapRegionLoader))
            ArtDetailViewport.setDefaultViewport(nextGLPictureSet.id,
                    bitmapRegionLoader.width * 1f / bitmapRegionLoader.height,
                    aspectRatio)
        }

        nextGLPictureSet.load(bitmapRegionLoader)

        crossfadeAnimator.start(0, 1) {
            // swap current and next picturesets
            val oldGLPictureSet = currentGLPictureSet
            currentGLPictureSet = nextGLPictureSet
            nextGLPictureSet = GLPictureSet(oldGLPictureSet.id)
            callbacks.requestRender()
            oldGLPictureSet.destroyPictures()
            if (!demoMode) {
                EventBus.getDefault().postSticky(SwitchingPhotosStateChangedEvent(
                        currentGLPictureSet.id, false))
            }
            System.gc()
            val loader = queuedNextBitmapRegionLoader
            if (loader != null) {
                setAndConsumeBitmapRegionLoader(loader)
                queuedNextBitmapRegionLoader = null
            }
        }
        callbacks.requestRender()
    }

    private inner class GLPictureSet internal constructor(internal val id: Int) {
        private val projectionMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)
        private val pictures = arrayOfNulls<GLPicture>(blurKeyframes + 1)
        private var hasBitmap = false
        private var bitmapAspectRatio = 1f
        internal var dimAmount = 0

        internal fun load(bitmapRegionLoader: BitmapRegionLoader) {
            hasBitmap = bitmapRegionLoader.width != 0 && bitmapRegionLoader.height != 0
            bitmapAspectRatio = if (hasBitmap)
                bitmapRegionLoader.width * 1f / bitmapRegionLoader.height
            else
                1f

            dimAmount = DEFAULT_MAX_DIM

            destroyPictures()

            if (hasBitmap) {
                val options = BitmapFactory.Options()
                val rect = Rect()
                val originalWidth = bitmapRegionLoader.width
                val originalHeight = bitmapRegionLoader.height

                // Calculate image darkness to determine dim amount
                rect.set(0, 0, originalWidth, originalHeight)
                options.inSampleSize = originalHeight.sampleSize(64)
                var tempBitmap = bitmapRegionLoader.decodeRegion(rect, options)
                val darkness = tempBitmap.darkness()
                dimAmount = if (demoMode)
                    DEMO_DIM
                else
                    (maxDim * (1 - DIM_RANGE + DIM_RANGE * Math.sqrt(darkness.toDouble()))).toInt()
                tempBitmap?.recycle()

                // Create the GLPicture objects
                pictures[0] = bitmapRegionLoader.toGLPicture(currentHeight)
                if (maxPrescaledBlurPixels == 0 && maxGrey == 0) {
                    for (f in 1..blurKeyframes) {
                        pictures[f] = pictures[0]
                    }
                } else {
                    val sampleSizeTargetHeight: Int = if (maxPrescaledBlurPixels > 0) {
                        currentHeight / blurredSampleSize
                    } else {
                        currentHeight
                    }
                    val scaledHeight: Int
                    val scaledWidth: Int

                    // Note that image width should be a multiple of 4 to avoid
                    // issues with RenderScript allocations.
                    scaledHeight = Math.max(2, sampleSizeTargetHeight.floorEven())
                    scaledWidth = Math.max(4, (scaledHeight * bitmapAspectRatio).toInt().roundMult4())

                    // To blur, first load the entire bitmap region, but at a very large
                    // sample size that's appropriate for the final blurred image
                    options.inSampleSize = originalHeight.sampleSize(sampleSizeTargetHeight)
                    rect.set(0, 0, originalWidth, originalHeight)
                    tempBitmap = bitmapRegionLoader.decodeRegion(rect, options)

                    if (tempBitmap != null
                            && tempBitmap.width != 0 && tempBitmap.height != 0) {
                        // Next, create a scaled down version of the bitmap so that the blur radius
                        // looks appropriate (tempBitmap will likely be bigger than the final
                        // blurred bitmap, and thus the blur may look smaller if we just used
                        // tempBitmap as the final blurred bitmap).

                        // Note that image width should be a multiple of 4 to avoid
                        // issues with RenderScript allocations.
                        val scaledBitmap = Bitmap.createScaledBitmap(
                                tempBitmap, scaledWidth, scaledHeight, true)
                        if (tempBitmap != scaledBitmap) {
                            tempBitmap.recycle()
                        }

                        // And finally, create a blurred copy for each keyframe.
                        val blurrer = ImageBlurrer(context, scaledBitmap)
                        for (f in 1..blurKeyframes) {
                            val desaturateAmount = maxGrey / 500f * f / blurKeyframes
                            val blurRadius = if (maxPrescaledBlurPixels > 0) {
                                blurRadiusAtFrame(f.toFloat())
                            } else {
                                0f
                            }
                            val blurredBitmap = blurrer.blurBitmap(blurRadius, desaturateAmount)
                            pictures[f] = blurredBitmap?.toGLPicture()
                            blurredBitmap?.recycle()
                        }
                        blurrer.destroy()

                        scaledBitmap.recycle()
                    } else {
                        Log.e(TAG, "BitmapRegionLoader failed to decode the region, rect=${rect.toShortString()}")
                        for (f in 1..blurKeyframes) {
                            pictures[f] = null
                        }
                    }
                }
                bitmapRegionLoader.destroy()
            }

            recomputeTransformMatrices()
            callbacks.requestRender()
        }

        internal fun recomputeTransformMatrices() {
            val screenToBitmapAspectRatio = aspectRatio / bitmapAspectRatio
            if (screenToBitmapAspectRatio == 0f) {
                return
            }

            // Ensure the bitmap is wider than the screen relatively by applying zoom
            // if necessary. Vary width but keep height the same.
            val zoom = Math.max(1f, 1.15f * screenToBitmapAspectRatio)

            // Total scale factors in both zoom and scale due to aspect ratio.
            val scaledBitmapToScreenAspectRatio = zoom / screenToBitmapAspectRatio

            // At most pan across 1.8 screenfuls (2 screenfuls + some parallax)
            // TODO: if we know the number of home screen pages, use that number here
            val maxPanScreenWidths = Math.min(1.8f, scaledBitmapToScreenAspectRatio)

            currentViewport.apply {
                left = interpolate(-1f, 1f,
                        interpolate(
                                (1 - maxPanScreenWidths / scaledBitmapToScreenAspectRatio) / 2,
                                (1 + (maxPanScreenWidths - 2) / scaledBitmapToScreenAspectRatio) / 2,
                                normalOffsetX))
                right = left + 2f / scaledBitmapToScreenAspectRatio
                bottom = -1f / zoom
                top = 1f / zoom
            }

            val focusAmount = (blurKeyframes - blurAnimator.currentValue) / blurKeyframes
            if (blurRelatedToArtDetailMode && focusAmount > 0) {
                val artDetailViewport = ArtDetailViewport.getViewport(id)
                if (artDetailViewport.width() == 0f || artDetailViewport.height() == 0f) {
                    if (!demoMode && !preview) {
                        // reset art detail viewport
                        ArtDetailViewport.setViewport(id,
                                uninterpolate(-1f, 1f, currentViewport.left),
                                uninterpolate(1f, -1f, currentViewport.top),
                                uninterpolate(-1f, 1f, currentViewport.right),
                                uninterpolate(1f, -1f, currentViewport.bottom))
                    }
                } else {
                    // interpolate
                    currentViewport.apply {
                        left = interpolate(
                                left,
                                interpolate(-1f, 1f, artDetailViewport.left),
                                focusAmount)
                        top = interpolate(
                                top,
                                interpolate(1f, -1f, artDetailViewport.top),
                                focusAmount)
                        right = interpolate(
                                right,
                                interpolate(-1f, 1f, artDetailViewport.right),
                                focusAmount)
                        bottom = interpolate(
                                bottom,
                                interpolate(1f, -1f, artDetailViewport.bottom),
                                focusAmount)
                    }
                }
            }

            Matrix.orthoM(projectionMatrix, 0,
                    currentViewport.left, currentViewport.right,
                    currentViewport.bottom, currentViewport.top,
                    1f, 10f)
        }

        internal fun drawFrame(globalAlpha: Float) {
            if (!hasBitmap) {
                return
            }

            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

            val blurFrame = blurAnimator.currentValue
            val lo = Math.floor(blurFrame.toDouble()).toInt()
            val hi = Math.ceil(blurFrame.toDouble()).toInt()

            val localHiAlpha = blurFrame - lo
            when {
                globalAlpha <= 0 -> {
                    // Nothing to draw
                }
                lo == hi -> {
                    // Just draw one
                    if (pictures[lo] == null) {
                        return
                    }

                    pictures[lo]?.draw(mvpMatrix, globalAlpha)
                }
                globalAlpha == 1f -> {
                    // Simple drawing
                    if (pictures[lo] == null || pictures[hi] == null) {
                        return
                    }

                    pictures[lo]?.draw(mvpMatrix, 1f)
                    pictures[hi]?.draw(mvpMatrix, localHiAlpha)
                }
                else -> {
                    // If there's both a global and local alpha, re-compose alphas, to
                    // effectively compose hi and lo before composing the result
                    // with the background.
                    //
                    // The math, where a1,a2 are previous alphas and b1,b2 are new alphas:
                    //   b1 = a1 * (a2 - 1) / (a1 * a2 - 1)
                    //   b2 = a1 * a2
                    if (pictures[lo] == null || pictures[hi] == null) {
                        return
                    }

                    val newLocalLoAlpha = globalAlpha * (localHiAlpha - 1) / (globalAlpha * localHiAlpha - 1)
                    val newLocalHiAlpha = globalAlpha * localHiAlpha
                    pictures[lo]?.draw(mvpMatrix, newLocalLoAlpha)
                    pictures[hi]?.draw(mvpMatrix, newLocalHiAlpha)
                }
            }
        }

        internal fun destroyPictures() {
            for (i in pictures.indices) {
                if (pictures[i] != null) {
                    pictures[i]?.destroy()
                    pictures[i] = null
                }
            }
        }
    }

    fun destroy() {
        currentGLPictureSet.destroyPictures()
        nextGLPictureSet.destroyPictures()
    }

    fun setIsBlurred(isBlurred: Boolean, artDetailMode: Boolean) {
        if (artDetailMode && !isBlurred && !demoMode && !preview) {
            // Reset art detail viewport
            ArtDetailViewport.setViewport(0, 0f, 0f, 0f, 0f)
            ArtDetailViewport.setViewport(1, 0f, 0f, 0f, 0f)
        }

        blurRelatedToArtDetailMode = artDetailMode
        this.isBlurred = isBlurred
        blurAnimator.start(endValue = if (isBlurred) blurKeyframes else 0) {
            if (isBlurred && artDetailMode) {
                System.gc()
            }
        }
        callbacks.requestRender()
    }

    interface Callbacks {
        fun requestRender()
    }
}
