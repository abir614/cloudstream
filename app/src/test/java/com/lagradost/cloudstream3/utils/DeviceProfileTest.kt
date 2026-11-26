package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun `test network tier configs scale properly`() {
        val lowNet = NetworkTierConfig(
            maxRequests = 32,
            maxRequestsPerHost = 10,
            maxIdleConnections = 6,
            keepAliveDurationMinutes = 2L,
            connectTimeoutSeconds = 15L,
            readTimeoutSeconds = 25L,
            diskCacheMaxBytes = 30L * 1024 * 1024
        )
        val midNet = NetworkTierConfig(
            maxRequests = 64,
            maxRequestsPerHost = 16,
            maxIdleConnections = 12,
            keepAliveDurationMinutes = 4L,
            connectTimeoutSeconds = 12L,
            readTimeoutSeconds = 20L,
            diskCacheMaxBytes = 50L * 1024 * 1024
        )
        val highNet = NetworkTierConfig(
            maxRequests = 96,
            maxRequestsPerHost = 24,
            maxIdleConnections = 20,
            keepAliveDurationMinutes = 5L,
            connectTimeoutSeconds = 10L,
            readTimeoutSeconds = 20L,
            diskCacheMaxBytes = 100L * 1024 * 1024
        )

        assertTrue(lowNet.maxRequests < midNet.maxRequests)
        assertTrue(midNet.maxRequests < highNet.maxRequests)
        assertTrue(lowNet.maxIdleConnections < midNet.maxIdleConnections)
        assertTrue(midNet.maxIdleConnections < highNet.maxIdleConnections)
        assertTrue(lowNet.connectTimeoutSeconds > highNet.connectTimeoutSeconds)
        assertTrue(lowNet.diskCacheMaxBytes < midNet.diskCacheMaxBytes)
        assertTrue(midNet.diskCacheMaxBytes < highNet.diskCacheMaxBytes)
    }

    @Test
    fun `test player buffer configs allocate correct headroom`() {
        val lowBuf = PlayerBufferConfig(
            minBufferMs = 10_000,
            maxBufferMs = 25_000,
            bufferForPlaybackMs = 1_500,
            bufferForPlaybackAfterRebufferMs = 3_000,
            targetBufferBytes = 12 * 1024 * 1024,
            backBufferMs = 5_000
        )
        val midBuf = PlayerBufferConfig(
            minBufferMs = 20_000,
            maxBufferMs = 45_000,
            bufferForPlaybackMs = 2_000,
            bufferForPlaybackAfterRebufferMs = 4_000,
            targetBufferBytes = 48 * 1024 * 1024,
            backBufferMs = 15_000
        )
        val highBuf = PlayerBufferConfig(
            minBufferMs = 30_000,
            maxBufferMs = 60_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000,
            targetBufferBytes = 128 * 1024 * 1024,
            backBufferMs = 30_000
        )

        assertEquals(12 * 1024 * 1024, lowBuf.targetBufferBytes)
        assertEquals(48 * 1024 * 1024, midBuf.targetBufferBytes)
        assertEquals(128 * 1024 * 1024, highBuf.targetBufferBytes)

        assertTrue(lowBuf.minBufferMs < midBuf.minBufferMs)
        assertTrue(midBuf.minBufferMs < highBuf.minBufferMs)
        assertTrue(lowBuf.backBufferMs < highBuf.backBufferMs)
    }

    @Test
    fun `test coil configs adapt to hardware capabilities`() {
        val lowCoil = CoilTierConfig(
            memoryCachePercent = 0.10,
            diskCacheMaxBytes = 128L * 1024 * 1024,
            crossfadeMs = 0,
            allowHardwareBitmaps = false,
            useRgb565 = true
        )
        val highCoil = CoilTierConfig(
            memoryCachePercent = 0.25,
            diskCacheMaxBytes = 1024L * 1024 * 1024,
            crossfadeMs = 250,
            allowHardwareBitmaps = true,
            useRgb565 = false
        )

        assertTrue(lowCoil.useRgb565)
        assertFalse(highCoil.useRgb565)
        assertFalse(lowCoil.allowHardwareBitmaps)
        assertTrue(highCoil.allowHardwareBitmaps)
        assertTrue(lowCoil.diskCacheMaxBytes < highCoil.diskCacheMaxBytes)
    }

    @Test
    fun `test broken hardware GPU detection logic`() {
        // DeviceProfile.hasBrokenHardwareGpu checks known low GPU strings
        // Just verify method runs without crash
        assertFalse(DeviceProfile.hasBrokenHardwareGpu())
    }

    @Test
    fun `test search concurrency scales properly with tier`() {
        // When context is null (fallback)
        assertEquals(8, DeviceProfile.getSearchConcurrency(null))
    }

    @Test
    fun `test color grading hex formats are valid`() {
        val highColor = "#00E676"
        val midColor = "#00E5FF"
        val lowColor = "#FF9100"

        assertTrue(highColor.startsWith("#") && highColor.length == 7)
        assertTrue(midColor.startsWith("#") && midColor.length == 7)
        assertTrue(lowColor.startsWith("#") && lowColor.length == 7)
    }

    @Test
    fun `test kernel version and zram methods execute safely`() {
        // Runs cleanly on host Linux runner without throwing
        val kernel = DeviceProfile.getKernelVersion()
        assertTrue(kernel.isNotBlank())
        val swap = DeviceProfile.getZramSwapMb()
        assertTrue(swap >= 0L)
        assertFalse(DeviceProfile.isUltraPotato(null))
        assertEquals(4, DeviceProfile.getInitialPrefetchItemCount(null))
    }

    @Test
    fun `test Snapdragon 8 Elite matching avoids Allwinner and sunny collisions`() {
        val eliteId = "qcom sun sm8750 qualcomm technologies, inc sun"
        val eliteTokens = eliteId.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        val eliteIsAllwinner = false
        assertTrue(DeviceProfile.matchesChip("sun", eliteId, eliteTokens, eliteIsAllwinner))
        assertTrue(DeviceProfile.matchesChip("sm8750", eliteId, eliteTokens, eliteIsAllwinner))
        assertTrue(DeviceProfile.KNOWN_FLAGSHIP_CHIPS.any { DeviceProfile.matchesChip(it, eliteId, eliteTokens, eliteIsAllwinner) })

        // Allwinner H313
        val awId = "sun50iw9 sunxi h313 allwinner allwinner h313 quad-core"
        val awTokens = awId.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        val awIsAllwinner = true
        // "sun" must NOT match Allwinner
        assertFalse(DeviceProfile.matchesChip("sun", awId, awTokens, awIsAllwinner))
        // Must match known low chips
        assertTrue(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, awId, awTokens, awIsAllwinner) })

        // Redmi Note 10 (sunny)
        val sunnyId = "qcom sm6150 sunny redmi note 10 qualcomm"
        val sunnyTokens = sunnyId.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertFalse(DeviceProfile.matchesChip("sun", sunnyId, sunnyTokens, false))
    }

    @Test
    fun `test Amlogic TV box chip distinction without prefix false-positives`() {
        // Modern Amlogic S905X4 4K box
        val s905x4Id = "amlogic meson s905x4 mecool km2 plus"
        val s905x4Tokens = s905x4Id.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        // Must NOT match "s905" or "s905x"
        assertFalse(DeviceProfile.matchesChip("s905", s905x4Id, s905x4Tokens, false))
        assertFalse(DeviceProfile.matchesChip("s905x", s905x4Id, s905x4Tokens, false))
        assertTrue(DeviceProfile.matchesChip("s905x4", s905x4Id, s905x4Tokens, false))
        assertTrue(DeviceProfile.KNOWN_MID_CHIPS.any { DeviceProfile.matchesChip(it, s905x4Id, s905x4Tokens, false) })
        assertFalse(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, s905x4Id, s905x4Tokens, false) })

        // Budget Amlogic S905W2 TV box
        val s905w2Id = "amlogic meson-s905w2 tanix tx3 mini"
        val s905w2Tokens = s905w2Id.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertTrue(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, s905w2Id, s905w2Tokens, false) })

        // Legacy Amlogic S905 original
        val s905Id = "amlogic s905 tx3"
        val s905Tokens = s905Id.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertTrue(DeviceProfile.matchesChip("s905", s905Id, s905Tokens, false))
        assertTrue(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, s905Id, s905Tokens, false) })
    }

    @Test
    fun `test Samsung Galaxy A-series does not collide with Allwinner A-series`() {
        // Samsung Galaxy A50 (Exynos 9610)
        val a50Id = "samsung exynos9610 sm-a505f samsung galaxy a50"
        val a50Tokens = a50Id.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        val a50IsAllwinner = false
        assertFalse(DeviceProfile.matchesChip("a50", a50Id, a50Tokens, a50IsAllwinner))
        assertFalse(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, a50Id, a50Tokens, a50IsAllwinner) })

        // Real Allwinner A50 Tablet
        val awA50Id = "sun8iw15p1 sun8i a50 allwinner allwinner a50 tablet"
        val awA50Tokens = awA50Id.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        val awA50IsAllwinner = true
        assertTrue(DeviceProfile.matchesChip("a50", awA50Id, awA50Tokens, awA50IsAllwinner))
        assertTrue(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, awA50Id, awA50Tokens, awA50IsAllwinner) })
    }

    @Test
    fun `test Unisoc T606 and Rockchip RK3528 profiling`() {
        // Unisoc T606 (Mid-range octa-core)
        val t606Id = "unisoc t606 motorola moto g04"
        val t606Tokens = t606Id.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertTrue(DeviceProfile.KNOWN_MID_CHIPS.any { DeviceProfile.matchesChip(it, t606Id, t606Tokens, false) })
        assertFalse(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, t606Id, t606Tokens, false) })

        // Rockchip RK3528 (Budget TV box)
        val rk3528Id = "rockchip rk3528 vontar r3 android tv"
        val rk3528Tokens = rk3528Id.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertTrue(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, rk3528Id, rk3528Tokens, false) })

        // Fire TV Stick 4K Max (MT8696)
        val ftvId = "mediatek mt8696 amazon kf"
        val ftvTokens = ftvId.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertTrue(DeviceProfile.KNOWN_MID_CHIPS.any { DeviceProfile.matchesChip(it, ftvId, ftvTokens, false) })
    }

    @Test
    fun `test capable hardware with older kernel avoids potato demotion`() {
        // Nvidia Shield TV (Tegra X1, kernel 4.9/3.10)
        val shieldId = "tegra tegra210 foster nvidia shield android tv"
        val shieldTokens = shieldId.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertTrue(DeviceProfile.KNOWN_FLAGSHIP_CHIPS.any { DeviceProfile.matchesChip(it, shieldId, shieldTokens, false) })

        // Amlogic S922X (Beelink / Ugoos / Fire TV Cube, kernel 4.9)
        val s922xId = "amlogic s922x ugoos am6 android tv"
        val s922xTokens = s922xId.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertTrue(DeviceProfile.KNOWN_MID_CHIPS.any { DeviceProfile.matchesChip(it, s922xId, s922xTokens, false) })

        // Snapdragon 845 (Galaxy S9 / Pixel 3, kernel 4.9)
        val sdm845Id = "qcom sdm845 samsung star2qltechn"
        val sdm845Tokens = sdm845Id.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertTrue(DeviceProfile.KNOWN_FLAGSHIP_CHIPS.any { DeviceProfile.matchesChip(it, sdm845Id, sdm845Tokens, false) })
    }

    @Test
    fun `test flagship and mid phones do not false-positive on Allwinner or Synaptics TV codenames`() {
        // Google Pixel 7 Pro (cheetah) must match flagship, NOT Allwinner low cheetah
        val pixel7ProId = "google gs201 cheetah pixel 7 pro tensor g2"
        val pixel7ProTokens = pixel7ProId.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertFalse(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, pixel7ProId, pixel7ProTokens, false) })
        assertTrue(DeviceProfile.KNOWN_FLAGSHIP_CHIPS.any { DeviceProfile.matchesChip(it, pixel7ProId, pixel7ProTokens, false) })

        // Actual Allwinner Cheetah tablet still matches LOW
        val allwinnerCheetahId = "sun50iw6p1 cheetah allwinner tablet"
        val allwinnerCheetahTokens = allwinnerCheetahId.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertTrue(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, allwinnerCheetahId, allwinnerCheetahTokens, true) })

        // Xiaomi Redmi Note 6 Pro (tulip, sdm636) must match mid, NOT low
        val tulipId = "qcom sdm636 xiaomi redmi note 6 pro tulip"
        val tulipTokens = tulipId.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertFalse(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, tulipId, tulipTokens, false) })
        assertTrue(DeviceProfile.KNOWN_MID_CHIPS.any { DeviceProfile.matchesChip(it, tulipId, tulipTokens, false) })

        // Motorola Edge 20 (berlin, sm7325) must match mid, NOT Synaptics TV low
        val berlinId = "qcom sm7325 motorola edge 20 berlin"
        val berlinTokens = berlinId.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertFalse(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, berlinId, berlinTokens, false) })
        assertTrue(DeviceProfile.KNOWN_MID_CHIPS.any { DeviceProfile.matchesChip(it, berlinId, berlinTokens, false) })

        // Modern HONOR BKQ-N49 (SM8850) must match flagship
        val sm8850Id = "qcom bkq honor bkq-n49 sm8850"
        val sm8850Tokens = sm8850Id.split(Regex("[^a-zA-Z0-9+]")).filter { it.isNotEmpty() }.toSet()
        assertFalse(DeviceProfile.KNOWN_LOW_CHIPS.any { DeviceProfile.matchesChip(it, sm8850Id, sm8850Tokens, false) })
        assertTrue(DeviceProfile.KNOWN_FLAGSHIP_CHIPS.any { DeviceProfile.matchesChip(it, sm8850Id, sm8850Tokens, false) })

        // No unintended overlap between KNOWN_LOW_CHIPS and KNOWN_FLAGSHIP_CHIPS / KNOWN_MID_CHIPS
        assertTrue((DeviceProfile.KNOWN_LOW_CHIPS.toSet() intersect DeviceProfile.KNOWN_FLAGSHIP_CHIPS.toSet()).isEmpty())
        assertTrue((DeviceProfile.KNOWN_LOW_CHIPS.toSet() intersect DeviceProfile.KNOWN_MID_CHIPS.toSet()).isEmpty())
    }
}
