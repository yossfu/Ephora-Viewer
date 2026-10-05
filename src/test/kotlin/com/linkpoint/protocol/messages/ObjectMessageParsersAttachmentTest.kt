package com.linkpoint.protocol.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class ObjectMessageParsersAttachmentTest {

    @Test
    fun normalObjectUpdatePreservesHudAttachmentPointFromStateByte() {
        val localId = 12345
        val fullId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val attachmentPoint = 34 // HUD Top Left
        val packet = buildPacket(localId, fullId, attachmentPoint)

        val updates = ObjectMessageParsers.parseObjectUpdate(packet)

        assertEquals(1, updates.size)
        assertEquals(localId, updates[0].localId)
        assertEquals(fullId, updates[0].fullId)
        assertEquals(attachmentPoint, updates[0].attachmentPoint)
    }

    @Test
    fun appendFlagIsRemovedFromHudAttachmentPoint() {
        val localId = 12346
        val fullId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val attachmentPoint = 32 // HUD Top Right
        val packet = buildPacket(localId, fullId, attachmentPoint or 0x80)

        val updates = ObjectMessageParsers.parseObjectUpdate(packet)

        assertEquals(1, updates.size)
        assertEquals(attachmentPoint, updates[0].attachmentPoint)
    }

    private fun buildPacket(localId: Int, fullId: UUID, state: Int): ByteArray {
        val buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putLong(0L) // region handle
        buffer.putShort(0) // time dilation
        buffer.put(1) // one ObjectData block

        buffer.putInt(localId)
        buffer.put(state.toByte())
        buffer.putLong(fullId.mostSignificantBits)
        buffer.putLong(fullId.leastSignificantBits)
        buffer.putInt(0) // CRC
        buffer.put(9) // PCode object
        buffer.put(0) // material
        buffer.put(0) // click action
        repeat(12) { buffer.put(0) } // scale
        buffer.put(60.toByte()) // object data length
        repeat(60) { buffer.put(0) } // position/velocity/rotation
        buffer.putInt(0) // parent id
        buffer.putInt(0) // update flags

        // 23-byte default PrimShapeParams
        buffer.put(0x10) // path curve
        buffer.put(0x01) // profile curve
        repeat(3) { buffer.putShort(0) }
        repeat(9) { buffer.put(0) }
        repeat(3) { buffer.putShort(0) }

        buffer.putShort(0) // texture entry length
        buffer.put(0) // texture animation length
        buffer.putShort(0) // name/value length
        buffer.putShort(0) // extra data length
        buffer.put(0) // hover text length
        repeat(4) { buffer.put(0) } // hover text color
        buffer.put(0) // media url length
        buffer.put(0) // particle system length
        buffer.put(0) // extra params length
        repeat(16) { buffer.put(0) } // sound UUID
        repeat(16) { buffer.put(0) } // owner UUID
        buffer.putFloat(0f) // sound gain
        buffer.put(0) // sound flags
        buffer.putFloat(0f) // sound radius
        buffer.put(0) // joint type
        repeat(12) { buffer.put(0) } // joint pivot
        repeat(12) { buffer.put(0) } // joint axis/anchor

        return buffer.array().copyOf(buffer.position())
    }
}
