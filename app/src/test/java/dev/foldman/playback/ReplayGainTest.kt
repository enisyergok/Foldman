package dev.foldman.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainTest {
    @Test
    fun readsTheNumberOutOfTheTag() {
        assertEquals(-6.54f, ReplayGain.parse("-6.54 dB")!!, 0.001f)
        assertEquals(3.2f, ReplayGain.parse("+3.20 dB")!!, 0.001f)
        assertEquals(-4.5f, ReplayGain.parse("-4,5 dB")!!, 0.001f)
        assertEquals(0f, ReplayGain.parse("0 dB")!!, 0f)
    }

    @Test
    fun ignoresWhatIsNotAGain() {
        assertNull(ReplayGain.parse(null))
        assertNull(ReplayGain.parse(""))
        assertNull(ReplayGain.parse("none"))
    }
}
