package io.github.vrivrixd.phonon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
    @Test fun comparesNumberByNumber() {
        assertTrue(UpdateManager.isNewer("1.10.0", "1.9.0"))
        assertTrue(UpdateManager.isNewer("1.1.0", "1.0.9"))
        assertTrue(UpdateManager.isNewer("2.0", "1.9.9"))
        assertFalse(UpdateManager.isNewer("1.9.0", "1.10.0"))
    }

    @Test fun theSameVersionIsNotNewer() {
        assertFalse(UpdateManager.isNewer("1.1.0", "1.1.0"))
        assertFalse(UpdateManager.isNewer("1.1", "1.1.0"))
    }

    @Test fun aDevBuildIsOlderThanAnyRelease() {
        assertTrue(UpdateManager.isNewer("1.1.0", "0.0.18-dev"))
    }
}
