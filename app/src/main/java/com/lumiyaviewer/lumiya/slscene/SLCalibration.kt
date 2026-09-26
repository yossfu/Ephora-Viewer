package com.lumiyaviewer.lumiya.slscene

import com.lumiyaviewer.lumiya.renderer.Renderer
import com.lumiyaviewer.lumiya.renderer.TextureDesc
import com.lumiyaviewer.lumiya.slproto.base.LLUUIDUtil
import com.lumiyaviewer.lumiya.slproto.base.Quaternion
import com.lumiyaviewer.lumiya.slproto.base.Vector3
import com.lumiyaviewer.lumiya.slproto.world.SceneObject
import com.lumiyaviewer.lumiya.slproto.world.TextureEntry
import com.lumiyaviewer.lumiya.slproto.world.UpdateSource
import com.lumiyaviewer.lumiya.slworld.SLObject
import com.lumiyaviewer.lumiya.slworld.SLTextureCache
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

object SLCalibration {

    const val TEXTURE_UUID = "77777777-7777-7777-7777-777777777777"

    const val FIRST_LOCAL_ID = 910001

    const val SIZE = 256

    const val CELLS = 8

    private const val PATH_LINE = 0x10

    private const val PROFILE_SQUARE = 0x01

    fun pixels(size: Int = SIZE, cells: Int = CELLS): ByteArray {
        val out = ByteArray(size * size * 4)
        val cell = size / cells
        val border = size / 16
        var o = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val checker = ((x / cell) + (y / cell)) % 2 == 0
                var r = if (checker) 255 else 0
                var g = if (checker) 255 else 0
                var b = if (checker) 255 else 0
                val cx = x / cell
                val cy = y / cell
                if (cx in 3..4 && cy in 3..4) {
                    r = 255
                    g = 0
                    b = 255
                } else if (y < border) {
                    r = 255
                    g = 0
                    b = 0
                } else if (y >= size - border) {
                    r = 0
                    g = 0
                    b = 255
                } else if (x < border) {
                    r = 0
                    g = 255
                    b = 0
                } else if (x >= size - border) {
                    r = 255
                    g = 255
                    b = 0
                }
                out[o++] = r.toByte()
                out[o++] = g.toByte()
                out[o++] = b.toByte()
                out[o++] = 255.toByte()
            }
        }
        return out
    }

    fun install(renderer: Renderer, textures: SLTextureCache): Boolean {
        if (textures.hasDecoded(TEXTURE_UUID)) return true
        return try {
            val pixels = pixels()
            val handle = renderer.createTexture(TextureDesc(SIZE, SIZE, pixels, false))
            textures.textureDecoded(TEXTURE_UUID, handle, false, pixels.size.toLong() * 4L / 3L)
            true
        } catch (error: Throwable) {
            false
        }
    }

    fun objects(): List<SLObject> {
        val list = ArrayList<SLObject>(3)
        list.add(box(FIRST_LOCAL_ID, "CAL-A 1x1", Vector3(-4.6f, -5.5f, 1.2f), 1f, 1f, 0f))
        list.add(box(FIRST_LOCAL_ID + 1, "CAL-B 3x2", Vector3(0f, -5.5f, 1.2f), 3f, 2f, 0f))
        list.add(box(FIRST_LOCAL_ID + 2, "CAL-C 2x2 rot90", Vector3(4.6f, -5.5f, 1.2f), 2f, 2f, (PI / 2).toFloat()))
        return list
    }

    private fun box(
        localId: Int,
        label: String,
        position: Vector3,
        repeatU: Float,
        repeatV: Float,
        rotation: Float
    ): SLObject {
        val sceneObject = SceneObject(
            localId = localId,
            uuid = "77777777-7777-7777-7777-7777777777" + String.format("%02d", localId - FIRST_LOCAL_ID + 1),
            pcode = SceneObject.PCODE_PRIM,
            position = position,
            rotation = Quaternion.IDENTITY,
            scale = Vector3(2.2f, 2.2f, 2.2f),
            pathCurve = PATH_LINE,
            profileCurve = PROFILE_SQUARE,
            pathBegin = 0,
            pathEnd = 0,
            pathScaleX = 100,
            pathScaleY = 100,
            pathShearX = 0,
            pathShearY = 0,
            pathTwist = 0,
            pathRadiusOffset = 0,
            pathTaperX = 0,
            pathTaperY = 0,
            pathRevolutions = 0,
            pathSkew = 0,
            profileBegin = 0,
            profileEnd = 0,
            profileHollow = 0,
            material = 3,
            textureId = TEXTURE_UUID,
            name = label
        )
        sceneObject.positionKnown = true
        sceneObject.textureEntry = TextureEntry.parse(entryBlob(repeatU, repeatV, rotation))
        sceneObject.noteShapeReceived(UpdateSource.LOCAL)
        sceneObject.revision = 1
        return SLObject.from(sceneObject, PrimGeometryNative.DETAIL_STANDARD)
    }

    private fun entryBlob(repeatU: Float, repeatV: Float, rotation: Float): ByteArray {
        val buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(LLUUIDUtil.toBytes(TEXTURE_UUID))
        buf.put(0)
        buf.put(byteArrayOf(0, 0, 0, 0))
        buf.put(0)
        buf.putFloat(repeatU)
        buf.put(0)
        buf.putFloat(repeatV)
        buf.put(0)
        buf.putShort(0)
        buf.put(0)
        buf.putShort(0)
        buf.put(0)
        buf.putShort(((rotation / (2f * PI.toFloat())) * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(ByteArray(16))
        buf.put(0)
        return buf.array()
    }
}
