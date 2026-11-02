package com.kevxngg.monitorroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test fun allowsCurrentAndUpToTwoBuildsBehind() {
        for (latest in 10..12) assertFalse(UpdatePolicy.isRequired(10, latest))
    }

    @Test fun requiresUpdateAtThreeBuildsBehindAndBeyond() {
        assertTrue(UpdatePolicy.isRequired(10, 13))
        assertTrue(UpdatePolicy.isRequired(10, 14))
    }

    @Test fun installedUpdateUnlocksTheApp() {
        assertFalse(UpdatePolicy.isRequired(13, 13))
        assertFalse(UpdatePolicy.isRequired(14, 13))
    }

    @Test fun unavailableVersionDoesNotLockTheApp() {
        assertFalse(UpdatePolicy.isRequired(0, 13))
        assertFalse(UpdatePolicy.isRequired(10, 0))
    }

    @Test fun optionalReleaseCanBeSkippedButManualCheckStillOffersIt() {
        assertFalse(UpdatePolicy.isOffered(10, 11, 11))
        assertTrue(UpdatePolicy.isOffered(10, 11, 11, ignoreSkipped = true))
    }

    @Test fun requiredReleaseCannotBeSkipped() {
        assertTrue(UpdatePolicy.isOffered(10, 13, 99))
    }

    @Test fun manualCheckDoesNotOfferReinstallOrDowngrade() {
        assertFalse(UpdatePolicy.isOffered(13, 13, 0, ignoreSkipped = true))
        assertFalse(UpdatePolicy.isOffered(14, 13, 0, ignoreSkipped = true))
    }
}
