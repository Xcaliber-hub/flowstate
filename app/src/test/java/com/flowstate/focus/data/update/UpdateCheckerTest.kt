package com.flowstate.focus.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun newerPatchIsNewer() = assertTrue(UpdateChecker.isNewer("1.1.5", "1.1.4"))
    @Test fun sameVersionIsNotNewer() = assertFalse(UpdateChecker.isNewer("1.1.4", "1.1.4"))
    @Test fun olderIsNotNewer() = assertFalse(UpdateChecker.isNewer("1.0.9", "1.1.4"))
    @Test fun numericNotLexical() = assertTrue(UpdateChecker.isNewer("1.1.10", "1.1.9"))
    @Test fun missingPartCountsAsZero() = assertTrue(UpdateChecker.isNewer("1.2", "1.1.9"))
    @Test fun suffixIgnored() = assertFalse(UpdateChecker.isNewer("1.1.4-beta", "1.1.4"))
}
