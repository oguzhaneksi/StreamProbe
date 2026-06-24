package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.DrmScheme
import com.streamprobe.sdk.model.DrmSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DrmSchemeDetectorCommonTest {
    @Test
    fun `mapUuidToScheme resolves known schemes`() {
        assertEquals(DrmScheme.WIDEVINE, DrmSchemeDetectorCommon.mapUuidToScheme("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"))
        assertEquals(DrmScheme.PLAYREADY, DrmSchemeDetectorCommon.mapUuidToScheme("9a04f079-9840-4286-ab92-e65be0885f95"))
        assertEquals(DrmScheme.CLEARKEY, DrmSchemeDetectorCommon.mapUuidToScheme("e2719d58-a985-b3c9-781a-b030af78d30e"))
    }

    @Test
    fun `mapUuidToScheme is case insensitive`() {
        assertEquals(DrmScheme.WIDEVINE, DrmSchemeDetectorCommon.mapUuidToScheme("EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED"))
    }

    @Test
    fun `mapUuidToScheme returns null for unknown uuid`() {
        assertNull(DrmSchemeDetectorCommon.mapUuidToScheme("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `mapDrmState maps the five known states`() {
        assertEquals(DrmSessionState.RELEASED, DrmSchemeDetectorCommon.mapDrmState(0))
        assertEquals(DrmSessionState.ERROR, DrmSchemeDetectorCommon.mapDrmState(1))
        assertEquals(DrmSessionState.OPENING, DrmSchemeDetectorCommon.mapDrmState(2))
        assertEquals(DrmSessionState.OPENED, DrmSchemeDetectorCommon.mapDrmState(3))
        assertEquals(DrmSessionState.OPENED_WITH_KEYS, DrmSchemeDetectorCommon.mapDrmState(4))
    }

    @Test
    fun `mapDrmState defaults to UNKNOWN for unrecognised state`() {
        assertEquals(DrmSessionState.UNKNOWN, DrmSchemeDetectorCommon.mapDrmState(-1))
    }
}
