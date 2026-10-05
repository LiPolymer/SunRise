package ink.lipoly.app.sunrise.catalog

import kotlin.test.*

class FrequencyResponseTest {
    @Test fun twoColumnWhitespaceAndCommaFilesPreserveMagnitude() {
        for (text in listOf(
            "\uFEFF* comment\r\n\r\n# another comment\r\n; final comment\r\n 100\t40 \r\n1000   60\r\n",
            "100, 40\n1000,60\n",
        )) {
            val response = parseFrequencyResponse(text.encodeToByteArray())
            assertContentEquals(doubleArrayOf(100.0, 1000.0), response.frequencyHz)
            assertContentEquals(doubleArrayOf(40.0, 60.0), response.splDb)
        }
    }

    @Test fun explicitlyIdentifiedRewPhaseDoesNotChangeMagnitude() {
        val first = parseFrequencyResponse("* Freq(Hz) SPL(dB) Phase(degrees)\n100 40 -90\n1000 60 180".encodeToByteArray())
        val second = parseFrequencyResponse("# Freq(Hz), SPL(dB), Phase(degrees)\n100,40,12\n1000,60,-12".encodeToByteArray())
        assertContentEquals(first.frequencyHz, second.frequencyHz)
        assertContentEquals(doubleArrayOf(40.0, 60.0), second.splDb)
    }

    @Test fun corruptUtf8IsAllowedOnlyInIgnoredComments() {
        val malformedComment = "* ".encodeToByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\n100 40\n1000 60".encodeToByteArray()
        assertEquals(2, parseFrequencyResponse(malformedComment).frequencyHz.size)
        val malformedData = "100 40\n1000 ".encodeToByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
        assertInvalid(malformedData, 2)
        assertInvalid("100 40\n1000 60\uFFFD".encodeToByteArray(), 2)
    }

    @Test fun nonfiniteInvalidAndNonIncreasingDataRejectTheEntireCurve() {
        for (badLine in listOf(
            "1000 NaN", "1000 Infinity", "1000 -Infinity", "NaN 60", "Infinity 60",
            "100 60", "50 60", "0 60", "-1 60", "1000 text", "1000", "1000 60 0",
            "1000,,60", "1000,60,", "1000 60 extra extra",
        )) assertInvalid("* comment\n100 40\n$badLine\n2000 70".encodeToByteArray(), 3)
    }

    @Test fun phaseMustBeFiniteAndColumnsMustRemainConsistent() {
        for (badLine in listOf("1000 60 NaN", "1000 60 Infinity", "1000 60", "1000 60 phase")) {
            assertInvalid("* Freq(Hz) SPL(dB) Phase(degrees)\n100 40 0\n$badLine".encodeToByteArray(), 3)
        }
        assertInvalid("* Freq(Hz) SPL(dB) Phase(degrees)\n100 40\n1000 60 0".encodeToByteArray(), 3)
        assertInvalid("* frequency magnitude unknown\n100 40 0\n1000 60 0".encodeToByteArray(), 2)
        assertInvalid("100 40 0\n* Freq(Hz) SPL(dB) Phase(degrees)\n1000 60 0".encodeToByteArray(), 1)
    }

    @Test fun fewerThanTwoPointsCannotCreateAReplacementCurve() {
        for (text in listOf("", "* comments only", "100 40")) {
            assertFailsWith<IllegalArgumentException> { parseFrequencyResponse(text.encodeToByteArray()) }
        }
    }

    @Test fun parserEnforcesIndividualAssetLimit() {
        assertFailsWith<IllegalArgumentException> { parseFrequencyResponse(ByteArray(MAX_RESPONSE_FILE_BYTES + 1)) }
    }

    private fun assertInvalid(bytes: ByteArray, line: Int) {
        val error = assertFailsWith<IllegalArgumentException> { parseFrequencyResponse(bytes) }
        assertTrue(error.message.orEmpty().contains("line $line"), error.message)
    }
}
