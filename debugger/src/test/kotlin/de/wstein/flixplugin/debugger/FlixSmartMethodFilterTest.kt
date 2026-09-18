package de.wstein.flixplugin.debugger

import de.wstein.flixplugin.FlixDebugCalls
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlixSmartMethodFilterTest {

    private val call = FlixDebugCalls.Call(
        "/project/Main.flix", 9, 5, 9, 16, "Tuning.path",
        "dev.flix.gen.Tuning\$Def\$path\$0abc123xyz09", "staticApply",
    )

    @Test
    fun `accepts only the compiler-selected generated definition`() {
        assertTrue(FlixSmartMethodFilter.matches(call, call.className(), "staticApply"))
        assertFalse(FlixSmartMethodFilter.matches(call, "dev.flix.gen.Tuning\$Def\$path", "staticApply"))
        assertFalse(FlixSmartMethodFilter.matches(call, call.className(), "applyFrame"))
    }
}
