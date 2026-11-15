package io.github.vrivrixd.phonon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportedVoicesTest {
    private fun safetensors(header: String, dataBytes: Int): ByteArray {
        val h = header.toByteArray()
        val size = ByteArray(8) { ((h.size.toLong() shr (8 * it)) and 0xff).toByte() }
        return size + h + ByteArray(dataBytes)
    }

    private val voiceHeader = """{"speaker_wavs":{"dtype":"F32","shape":[1,512,125],"data_offsets":[0,256000]}}"""

    @Test fun aVoiceFileIsAccepted() {
        assertTrue(ImportedVoices.isVoiceFile(safetensors(voiceHeader, 256000)))
    }

    @Test fun headerMetadataAndSpacesDoNotMatter() {
        val header = """{"speaker_wavs": {"dtype": "F32", "shape": [1, 512, 125], "data_offsets": [0, 256000]}, "__metadata__": {"sig": "x"}}"""
        assertTrue(ImportedVoices.isVoiceFile(safetensors(header, 256000)))
    }

    @Test fun otherFilesAreRefused() {
        assertFalse(ImportedVoices.isVoiceFile(ByteArray(0)))
        assertFalse(ImportedVoices.isVoiceFile("hello world, not a voice".toByteArray()))
        assertFalse(ImportedVoices.isVoiceFile(safetensors(voiceHeader, 1000)))
        assertFalse(ImportedVoices.isVoiceFile(safetensors(voiceHeader.replace("F32", "F16"), 256000)))
        assertFalse(ImportedVoices.isVoiceFile(safetensors(voiceHeader.replace("speaker_wavs", "weights"), 256000)))
    }

    @Test fun theBundledVoicesPass() {
        val voice = File("../novas vozes").listFiles()?.firstOrNull() ?: return
        assertTrue(ImportedVoices.isVoiceFile(voice.readBytes()))
    }

    @Test fun namesAreChecked() {
        assertTrue(ImportedVoices.validName("Amanda"))
        assertFalse(ImportedVoices.validName(""))
        assertFalse(ImportedVoices.validName("a".repeat(81)))
        assertFalse(ImportedVoices.validName("tab\there"))
    }
}
