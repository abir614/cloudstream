package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.ui.coordinators.DeepLinkAction
import com.lagradost.cloudstream3.ui.coordinators.DeepLinkCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeepLinkCoordinatorTest {

    private lateinit var coordinator: DeepLinkCoordinator

    @Before
    fun setup() {
        coordinator = DeepLinkCoordinator()
    }

    @Test
    fun `test null and empty urls return None action`() {
        assertEquals(DeepLinkAction.None, coordinator.parseIntentUrl(null))
        assertEquals(DeepLinkAction.None, coordinator.parseIntentUrl(""))
        assertEquals(DeepLinkAction.None, coordinator.parseIntentUrl("   "))
    }

    @Test
    fun `test repo shortlink evaluates to LoadRepository`() {
        val url = "https://cs.repo?raw.githubusercontent.com/repo/plugins.json"
        val action = coordinator.parseIntentUrl(url)
        assertTrue(action is DeepLinkAction.LoadRepository)
        assertEquals("https://raw.githubusercontent.com/repo/plugins.json", (action as DeepLinkAction.LoadRepository).url)
    }

    @Test
    fun `test custom repo scheme evaluates to LoadRepository`() {
        val url = "cloudstreamrepo://my-custom-plugin-repo.org/index.json"
        val action = coordinator.parseIntentUrl(url)
        assertTrue(action is DeepLinkAction.LoadRepository)
        assertEquals("https://my-custom-plugin-repo.org/index.json", (action as DeepLinkAction.LoadRepository).url)
    }

    @Test
    fun `test search intent decodes query properly`() {
        val url = "cloudstreamsearch://Attack%20on%20Titan"
        val action = coordinator.parseIntentUrl(url)
        assertTrue(action is DeepLinkAction.SearchQuery)
        assertEquals("Attack on Titan", (action as DeepLinkAction.SearchQuery).query)
    }

    @Test
    fun `test hot reload adb intent triggers HotReload action`() {
        val url = "cloudstream:"
        val action = coordinator.parseIntentUrl(url)
        assertEquals(DeepLinkAction.HotReload, action)
    }

    @Test
    fun `test auth redirect evaluates to AccountAuth`() {
        val url = "https://my-auth-callback.com/cloudstream?token=xyz123"
        val action = coordinator.parseIntentUrl(url)
        assertTrue(action is DeepLinkAction.AccountAuth)
        assertEquals(url, (action as DeepLinkAction.AccountAuth).url)
    }

    @Test
    fun `test http video streaming link evaluates to RawProviderMedia`() {
        val url = "https://streamprovider.to/watch/12345"
        val action = coordinator.parseIntentUrl(url)
        assertTrue(action is DeepLinkAction.RawProviderMedia)
        assertEquals(url, (action as DeepLinkAction.RawProviderMedia).url)
    }
}
