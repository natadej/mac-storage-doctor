package com.natadej.storagedoctor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FileAccessTest {
    @Test fun firstLaunchNeedsDisclosure() {
        assertEquals(FileAccessMode.DISCLOSURE, fileAccessMode(false, false))
    }

    @Test fun denialAllowsLimitedMode() {
        assertEquals(FileAccessMode.LIMITED, fileAccessMode(false, true))
    }

    @Test fun grantEnablesFilesEvenAfterLimitedMode() {
        assertEquals(FileAccessMode.FULL, fileAccessMode(true, true))
        assertEquals(FileAccessMode.FULL, fileAccessMode(true, false))
    }

    @Test fun revocationNeverLeavesFullMode() {
        assertEquals(FileAccessMode.LIMITED, fileAccessMode(false, true))
        assertEquals(FileAccessMode.DISCLOSURE, fileAccessMode(false, false))
    }
}
