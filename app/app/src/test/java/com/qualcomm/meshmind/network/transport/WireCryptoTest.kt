package com.qualcomm.meshmind.network.transport

import com.qualcomm.meshmind.packet.builder.PacketBuilder
import com.qualcomm.meshmind.packet.checksum.ChecksumCalculator
import com.qualcomm.meshmind.packet.models.PacketType
import com.qualcomm.meshmind.packet.parser.PacketParser
import com.qualcomm.meshmind.packet.serializer.PacketSerializer
import com.qualcomm.meshmind.packet.validation.FrameValidator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

class WireCryptoTest {

    @Test
    fun `logical plaintext frame encrypts to valid wire frame and decrypts to original logical frame`() {
        val crypto = PeerSessionCrypto()
        // Simulate derivation
        crypto.deriveSessionKey(crypto.getPublicKey(), "node_a", "node_b")

        val payloadText = "Secret message"
        val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)
        val logicalChecksum = ChecksumCalculator.calculateCrc32(payloadBytes)

        val plaintextFrame = PacketBuilder()
            .setPacketId(UUID.randomUUID().toString())
            .setSourceNodeId("node_a")
            .setDestinationNodeId("node_b")
            .setPacketType(PacketType.DATA)
            .setPriority(2)
            .setPayload(payloadBytes)
            .setChecksum(logicalChecksum)
            .build()

        assertTrue("Logical plaintext frame should be valid", FrameValidator.validateFrame(plaintextFrame))

        // --- ENCRYPT (Simulate TransportSession.sendFrame) ---
        val (nonce, ciphertext) = crypto.encryptPayload(plaintextFrame.payload)
        val envelope = ByteArrayOutputStream()
        val dos = DataOutputStream(envelope)
        dos.writeByte(1)
        dos.writeByte(nonce.size)
        dos.write(nonce)
        dos.write(ciphertext)
        dos.flush()
        
        val encryptedPayload = envelope.toByteArray()
        val encryptedChecksum = ChecksumCalculator.calculateCrc32(encryptedPayload)
        
        val wireFrame = plaintextFrame.copy(
            payload = encryptedPayload,
            checksum = encryptedChecksum
        )

        // Serialize to raw bytes
        val rawBytes = PacketSerializer.serialize(wireFrame)

        // --- DECRYPT (Simulate TransportSession.startReadLoop) ---
        // 1. Parse wire frame
        val parsedWireFrame = PacketParser.parse(rawBytes)
        
        // 2. Validate wire frame
        assertTrue("Transport wire frame must be valid with its encrypted payload checksum", FrameValidator.validateFrame(parsedWireFrame))

        // 3. Unwrap envelope
        val dis = DataInputStream(ByteArrayInputStream(parsedWireFrame.payload))
        val cryptoVersion = dis.readByte()
        assertEquals(1, cryptoVersion.toInt())
        val nonceLength = dis.readByte().toInt()
        val readNonce = ByteArray(nonceLength)
        dis.readFully(readNonce)
        val readCiphertext = ByteArray(dis.available())
        dis.readFully(readCiphertext)

        // 4. Decrypt payload
        val decryptedPayload = crypto.decryptPayload(readNonce, readCiphertext)
        
        // 5. Reconstruct logical frame
        val reconstructedChecksum = ChecksumCalculator.calculateCrc32(decryptedPayload)
        val reconstructedFrame = parsedWireFrame.copy(
            payload = decryptedPayload,
            checksum = reconstructedChecksum
        )

        assertTrue("Reconstructed logical frame must be valid", FrameValidator.validateFrame(reconstructedFrame))
        assertArrayEquals("Payload should match original plaintext", payloadBytes, reconstructedFrame.payload)
        assertEquals("Checksums should match original", logicalChecksum, reconstructedFrame.checksum)
    }

    @Test
    fun `multi hop relay preserves logical checksum validity across multiple encryption boundaries`() {
        val cryptoAB = PeerSessionCrypto()
        cryptoAB.deriveSessionKey(cryptoAB.getPublicKey(), "node_a", "node_b")

        val cryptoBC = PeerSessionCrypto()
        cryptoBC.deriveSessionKey(cryptoBC.getPublicKey(), "node_b", "node_c")

        val payloadBytes = "Hop hop hop".toByteArray(Charsets.UTF_8)
        val logicalChecksum = ChecksumCalculator.calculateCrc32(payloadBytes)

        val plaintextFrame = PacketBuilder()
            .setPacketId(UUID.randomUUID().toString())
            .setSourceNodeId("node_a")
            .setDestinationNodeId("node_c") // Final destination
            .setPacketType(PacketType.DATA)
            .setPriority(2)
            .setPayload(payloadBytes)
            .setChecksum(logicalChecksum)
            .build()

        // 1. A -> B (Encrypt with K_AB)
        val (nonceAB, cipherAB) = cryptoAB.encryptPayload(plaintextFrame.payload)
        val envAB = ByteArrayOutputStream()
        val dosAB = DataOutputStream(envAB)
        dosAB.writeByte(1)
        dosAB.writeByte(nonceAB.size)
        dosAB.write(nonceAB)
        dosAB.write(cipherAB)
        val encryptedPayloadAB = envAB.toByteArray()
        val wireFrameAB = plaintextFrame.copy(
            payload = encryptedPayloadAB,
            checksum = ChecksumCalculator.calculateCrc32(encryptedPayloadAB)
        )
        
        // 2. B receives and decrypts
        assertTrue("B must accept wire frame AB", FrameValidator.validateFrame(wireFrameAB))
        val disAB = DataInputStream(ByteArrayInputStream(wireFrameAB.payload))
        disAB.readByte() // skip version
        val nLenAB = disAB.readByte().toInt()
        val nAB = ByteArray(nLenAB)
        disAB.readFully(nAB)
        val cAB = ByteArray(disAB.available())
        disAB.readFully(cAB)
        val decryptedAB = cryptoAB.decryptPayload(nAB, cAB)
        val reconstructedB = wireFrameAB.copy(
            payload = decryptedAB,
            checksum = ChecksumCalculator.calculateCrc32(decryptedAB)
        )

        // 3. B -> C (Encrypt with K_BC)
        val (nonceBC, cipherBC) = cryptoBC.encryptPayload(reconstructedB.payload)
        val envBC = ByteArrayOutputStream()
        val dosBC = DataOutputStream(envBC)
        dosBC.writeByte(1)
        dosBC.writeByte(nonceBC.size)
        dosBC.write(nonceBC)
        dosBC.write(cipherBC)
        val encryptedPayloadBC = envBC.toByteArray()
        val wireFrameBC = reconstructedB.copy(
            payload = encryptedPayloadBC,
            checksum = ChecksumCalculator.calculateCrc32(encryptedPayloadBC)
        )

        // 4. C receives and decrypts
        assertTrue("C must accept wire frame BC", FrameValidator.validateFrame(wireFrameBC))
        val disBC = DataInputStream(ByteArrayInputStream(wireFrameBC.payload))
        disBC.readByte() // skip version
        val nLenBC = disBC.readByte().toInt()
        val nBC = ByteArray(nLenBC)
        disBC.readFully(nBC)
        val cBC = ByteArray(disBC.available())
        disBC.readFully(cBC)
        val decryptedBC = cryptoBC.decryptPayload(nBC, cBC)
        val reconstructedC = wireFrameBC.copy(
            payload = decryptedBC,
            checksum = ChecksumCalculator.calculateCrc32(decryptedBC)
        )

        assertTrue("Final C frame must be logically valid", FrameValidator.validateFrame(reconstructedC))
        assertArrayEquals("C must see original payload", payloadBytes, reconstructedC.payload)
    }
}
