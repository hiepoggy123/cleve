package tribixbite.cleverkeys

import org.junit.Test
import org.junit.Assert.assertEquals

class TelexTest {
    @Test
    fun testNhieu() {
        assertTypingResult("nhieeuf", "nhiều")
    }

    @Test
    fun testTest() {
        assertTypingResult("tesst", "test")
        assertTypingResult("tess", "tes")
    }

    @Test
    fun testCafe() {
        assertTypingResult("caffe", "cafe")
        assertTypingResult("caff", "caf")
    }

    @Test
    fun testMuwa() {
        assertTypingResult("muww", "muw")
        assertTypingResult("muwwa", "muwa")
    }

    private fun assertTypingResult(input: String, expected: String) {
        val processor = tribixbite.cleverkeys.VietnameseTelexProcessor
        var text = ""
        for (ch in input) {
            val result = processor.processTelex(text, ch)
            if (result == null) {
                text += ch
            } else {
                text = text.dropLast(result.charsToDelete) + result.newWord
            }
        }
        assertEquals(expected, text)
    }
}
