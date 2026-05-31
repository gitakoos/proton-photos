/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
 *
 * This file is part of Photos for Proton.
 *
 * Photos for Proton is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package eu.akoos.photos.presentation.editor

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GL plumbing for the video re-encode pipeline.
 *
 * Architecture mirrors the canonical AOSP CTS pattern (android.media.cts.OutputSurface
 * + InputSurface) — that pair has been the reference implementation for Android media
 * re-encode since API 18 and works on every device the engine targets.
 *
 *   decoder ──(MediaCodec output Surface)──> [OutputSurface] ──SurfaceTexture──>
 *                                                   │
 *                                                   ▼ GL draw (cropMatrix * rotMatrix)
 *                                                   │
 *   muxer  <──(MediaCodec input Surface)── [InputSurface] ◄──EGL eglMakeCurrent───┘
 *
 * - [InputSurface] owns the EGL display/context/surface tied to the encoder's input
 *   surface. Make it current to draw into the encoder.
 * - [OutputSurface] owns an external-OES texture wired through a [SurfaceTexture] to
 *   a [Surface]. Hand that Surface to the decoder as its output target. When a frame
 *   arrives we updateTexImage() and draw the texture onto the encoder's surface with
 *   a 4x4 transform combining (a) SurfaceTexture's intrinsic transform, (b) crop, and
 *   (c) user rotation.
 *
 * The CTS pattern punts the H.264 colour-format compatibility quagmire entirely — the
 * encoder receives raw RGBA via OpenGL, not a per-device YUV layout, so we sidestep
 * the "works on Pixel, breaks on Samsung" pitfall that every YUV-buffer re-encoder
 * eventually hits.
 */
internal class InputSurface(surface: Surface) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surface: Surface? = surface

    init {
        eglSetup(surface)
    }

    private fun eglSetup(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            throw RuntimeException("unable to initialize EGL14")
        }
        // EGL_RECORDABLE_ANDROID hints the driver that the surface will feed a media
        // encoder — critical on some Adreno drivers that otherwise pick a config that
        // can't be read by the codec.
        val EGL_RECORDABLE_ANDROID = 0x3142
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            throw RuntimeException("unable to find RGB888+recordable ES2 EGL config")
        }
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE,
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        checkEglError("eglCreateContext")
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface")
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun release() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        surface?.release()
        surface = null
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun checkEglError(op: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("$op: EGL error 0x${Integer.toHexString(error)}")
        }
    }
}

/**
 * Wraps an EXTERNAL_OES texture in a SurfaceTexture + Surface that the decoder can write
 * decoded frames into. [awaitNewImage] blocks the encoder thread until a frame is ready,
 * then [drawImage] composes it onto the current EGL surface via [TextureRenderer].
 */
internal class OutputSurface : SurfaceTexture.OnFrameAvailableListener {

    private val textureRenderer = TextureRenderer()
    private var surfaceTexture: SurfaceTexture? = null
    val surface: Surface

    private val frameSyncObject = Object()
    private var frameAvailable = false

    init {
        textureRenderer.surfaceCreated()
        surfaceTexture = SurfaceTexture(textureRenderer.textureId)
        surfaceTexture!!.setOnFrameAvailableListener(this)
        surface = Surface(surfaceTexture)
    }

    fun release() {
        surface.release()
        surfaceTexture?.release()
        surfaceTexture = null
    }

    fun awaitNewImage(timeoutMs: Long = 5_000L) {
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                try {
                    frameSyncObject.wait(timeoutMs)
                    if (!frameAvailable) {
                        throw RuntimeException("Decoder did not produce a frame within ${timeoutMs}ms")
                    }
                } catch (ie: InterruptedException) {
                    throw RuntimeException(ie)
                }
            }
            frameAvailable = false
        }
        // SurfaceTexture.updateTexImage and the renderer's draw() must happen on the same
        // EGL context that hosts the external-OES texture — caller arranges that via
        // InputSurface.makeCurrent() before calling us.
        surfaceTexture!!.updateTexImage()
    }

    /**
     * Draws the latest decoded frame into the current EGL surface.
     *
     * @param transformMatrix combined 4x4 transform applied to texture coordinates. Built
     *     by the caller as (cropMatrix * rotationMatrix) * surfaceTextureTransform.
     */
    fun drawImage(transformMatrix: FloatArray) {
        textureRenderer.drawFrame(surfaceTexture!!, transformMatrix)
    }

    /** Pulls the SurfaceTexture's intrinsic transform (handles BT.601 vs BT.709, vertical
     *  flips that the decoder may have baked in). Caller composes this with crop+rotate. */
    fun getSurfaceTextureTransform(out: FloatArray) {
        surfaceTexture!!.getTransformMatrix(out)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        synchronized(frameSyncObject) {
            if (frameAvailable) {
                // CTS code logs but doesn't throw — the decoder occasionally produces a
                // duplicate frame at start; dropping the duplicate is the safe move.
            }
            frameAvailable = true
            frameSyncObject.notifyAll()
        }
    }
}

/**
 * Minimal external-OES texture quad shader. Multiplies texture coords through a 4x4
 * matrix combining SurfaceTexture's intrinsic transform with our crop+rotate transform.
 *
 * The shader is verbatim from the CTS TextureRender reference except for one addition:
 * we accept a user-supplied 4x4 in addition to SurfaceTexture's transform, then combine
 * them in the vertex shader. That keeps the cropping math in matrix space (cheap on the
 * GPU) instead of doing texture-coordinate arithmetic per-vertex.
 */
private class TextureRenderer {

    var textureId: Int = -1
        private set

    private var program: Int = 0
    private var uMVPMatrixLoc: Int = 0
    private var uSTMatrixLoc: Int = 0
    private var aPositionLoc: Int = 0
    private var aTextureCoordLoc: Int = 0

    // Full-screen quad — positions in clip space, tex coords map 1:1.
    private val triangleVertices: FloatBuffer = ByteBuffer
        .allocateDirect(VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(VERTICES).position(0) }

    fun surfaceCreated() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) throw RuntimeException("failed creating program")
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun drawFrame(st: SurfaceTexture, mvpMatrix: FloatArray) {
        val stMatrix = FloatArray(16)
        st.getTransformMatrix(stMatrix)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        triangleVertices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, STRIDE, triangleVertices)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        triangleVertices.position(POS_OFFSET)
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, STRIDE, triangleVertices)
        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glFinish()
    }

    private fun createProgram(vsSrc: String, fsSrc: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("Could not link program: $log")
        }
        return p
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $log")
        }
        return shader
    }

    companion object {
        private const val FLOAT_SIZE = 4
        private const val STRIDE = (3 + 2) * FLOAT_SIZE
        private const val POS_OFFSET = 3

        // Fullscreen quad: (x, y, z, u, v)
        private val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
             1f, -1f, 0f, 1f, 0f,
            -1f,  1f, 0f, 0f, 1f,
             1f,  1f, 0f, 1f, 1f,
        )

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}

/**
 * Builds the 4x4 transform applied to the SurfaceTexture's input quad so the cropped
 * subrect fills the encoder's output frame.
 *
 * - When [cropRect] is null we return identity (the full source frame fills the encoder).
 * - When [cropRect] is set we scale by (srcW / cropW, srcH / cropH) and translate by the
 *   crop offset, so only the cropped region maps onto the encoder's NDC quad.
 *
 * The rotation step is intentionally NOT applied here when the muxer's orientation hint
 * carries the rotation — see VideoReencoder for why we always burn rotation in pixels
 * during a re-encode (the muxer hint applies to the file, but a re-encoded output with
 * a cropped aspect ratio benefits from having the rotation already baked in so a player
 * doesn't double-apply it).
 */
internal object CropMatrix {

    fun build(
        sourceWidth: Int,
        sourceHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
    ): FloatArray {
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)

        // Burn rotation in pixels. NDC space is [-1, 1] on both axes.
        if (rotationDegrees % 360 != 0) {
            Matrix.rotateM(matrix, 0, -rotationDegrees.toFloat(), 0f, 0f, 1f)
        }

        // No crop → done.
        if (cropWidth >= sourceWidth && cropHeight >= sourceHeight && cropLeft == 0 && cropTop == 0) {
            return matrix
        }

        // SurfaceTexture's texture coords range over the FULL source. To make only the
        // cropped subrect fill the encoder's output quad we manipulate the vertex
        // transform: NDC quad spans (-1..1)² but we want it to expose only the cropped
        // texture region. Equivalent to a vertex-space scale + translate that maps
        // (-1..1) NDC ↔ (crop/source) texture region.
        val sx = sourceWidth.toFloat() / cropWidth.toFloat()
        val sy = sourceHeight.toFloat() / cropHeight.toFloat()
        // Translate is in NDC units. The center of the crop should land at NDC (0, 0).
        val tx = 1f - 2f * (cropLeft + cropWidth / 2f) / sourceWidth
        val ty = -(1f - 2f * (cropTop + cropHeight / 2f) / sourceHeight)

        // Order: scale first (around origin), then shift. Pre-multiply onto the rotation.
        val cropM = FloatArray(16)
        Matrix.setIdentityM(cropM, 0)
        Matrix.translateM(cropM, 0, tx * sx, ty * sy, 0f)
        Matrix.scaleM(cropM, 0, sx, sy, 1f)
        // Apply crop, then rotate (so the rotation is around the cropped region's centre).
        val combined = FloatArray(16)
        Matrix.multiplyMM(combined, 0, matrix, 0, cropM, 0)
        return combined
    }
}
