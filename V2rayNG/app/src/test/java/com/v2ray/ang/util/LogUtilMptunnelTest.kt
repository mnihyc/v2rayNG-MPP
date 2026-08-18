package com.v2ray.ang.util

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Test

class LogUtilMptunnelTest {

    @Test
    fun nativeLevelsMapToAndroidPrioritiesWithoutTheXrayThreshold() {
        assertEquals(Log.ERROR, LogUtil.mptunnelPriority("error"))
        assertEquals(Log.WARN, LogUtil.mptunnelPriority("warn"))
        assertEquals(Log.INFO, LogUtil.mptunnelPriority("info"))
        assertEquals(Log.DEBUG, LogUtil.mptunnelPriority("debug"))
    }

    @Test
    fun unknownNativeLevelFallsBackSafelyToWarning() {
        assertEquals(Log.WARN, LogUtil.mptunnelPriority("unexpected"))
    }
}
