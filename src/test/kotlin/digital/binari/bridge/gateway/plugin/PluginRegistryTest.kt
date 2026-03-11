package digital.binari.bridge.gateway.plugin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class PluginRegistryTest {

    private lateinit var pluginA: GatewayPlugin
    private lateinit var pluginB: GatewayPlugin
    private lateinit var pluginC: GatewayPlugin
    private lateinit var pluginConfigProperties: PluginConfigProperties
    private lateinit var registry: PluginRegistry

    @BeforeEach
    fun setUp() {
        pluginA = mockk(relaxed = true) {
            every { id } returns "plugin-a"
            every { name } returns "Plugin A"
            every { version } returns "1.0.0"
            every { phase } returns PluginPhase.PRE_ROUTE
            every { order } returns 20
            every { isEnabled() } returns true
            every { healthCheck() } returns Mono.just(PluginHealth(healthy = true))
        }

        pluginB = mockk(relaxed = true) {
            every { id } returns "plugin-b"
            every { name } returns "Plugin B"
            every { version } returns "1.0.0"
            every { phase } returns PluginPhase.PRE_ROUTE
            every { order } returns 10
            every { isEnabled() } returns true
            every { healthCheck() } returns Mono.just(PluginHealth(healthy = true))
        }

        pluginC = mockk(relaxed = true) {
            every { id } returns "plugin-c"
            every { name } returns "Plugin C"
            every { version } returns "2.0.0"
            every { phase } returns PluginPhase.POST_ROUTE
            every { order } returns 5
            every { isEnabled() } returns true
            every { healthCheck() } returns Mono.just(PluginHealth(healthy = true))
        }
    }

    @Test
    fun `plugins are loaded and initialized from config`() {
        pluginConfigProperties = PluginConfigProperties(
            configs = mapOf(
                "plugin-a" to PluginConfig(enabled = true, settings = mapOf("key" to "value")),
                "plugin-b" to PluginConfig(enabled = false)
            )
        )

        registry = PluginRegistry(listOf(pluginA, pluginB, pluginC), pluginConfigProperties)
        registry.initialize()

        // plugin-a should be initialized (enabled in config)
        verify(exactly = 1) { pluginA.initialize(mapOf("key" to "value")) }

        // plugin-b should NOT be initialized (disabled in config)
        verify(exactly = 0) { pluginB.initialize(any()) }

        // plugin-c should NOT be initialized (not in config)
        verify(exactly = 0) { pluginC.initialize(any()) }
    }

    @Test
    fun `getPlugin returns plugin by id`() {
        pluginConfigProperties = PluginConfigProperties()
        registry = PluginRegistry(listOf(pluginA, pluginB), pluginConfigProperties)
        registry.initialize()

        assertNotNull(registry.getPlugin("plugin-a"))
        assertNotNull(registry.getPlugin("plugin-b"))
        assertNull(registry.getPlugin("nonexistent"))
    }

    @Test
    fun `getEnabledPlugins returns only enabled plugins for the given phase`() {
        pluginConfigProperties = PluginConfigProperties(
            configs = mapOf(
                "plugin-a" to PluginConfig(enabled = true),
                "plugin-b" to PluginConfig(enabled = true),
                "plugin-c" to PluginConfig(enabled = true)
            )
        )

        registry = PluginRegistry(listOf(pluginA, pluginB, pluginC), pluginConfigProperties)
        registry.initialize()

        val preRoutePlugins = registry.getEnabledPlugins(PluginPhase.PRE_ROUTE)
        assertEquals(2, preRoutePlugins.size)
        assertEquals("plugin-b", preRoutePlugins[0].id) // order 10 first
        assertEquals("plugin-a", preRoutePlugins[1].id) // order 20 second

        val postRoutePlugins = registry.getEnabledPlugins(PluginPhase.POST_ROUTE)
        assertEquals(1, postRoutePlugins.size)
        assertEquals("plugin-c", postRoutePlugins[0].id)
    }

    @Test
    fun `getEnabledPlugins returns sorted by order`() {
        pluginConfigProperties = PluginConfigProperties(
            configs = mapOf(
                "plugin-a" to PluginConfig(enabled = true),
                "plugin-b" to PluginConfig(enabled = true)
            )
        )

        registry = PluginRegistry(listOf(pluginA, pluginB), pluginConfigProperties)
        registry.initialize()

        val plugins = registry.getEnabledPlugins(PluginPhase.PRE_ROUTE)
        assertEquals(2, plugins.size)
        // plugin-b (order 10) should come before plugin-a (order 20)
        assertTrue(plugins[0].order <= plugins[1].order)
        assertEquals("plugin-b", plugins[0].id)
        assertEquals("plugin-a", plugins[1].id)
    }

    @Test
    fun `enablePlugin initializes and adds plugin to enabled set`() {
        pluginConfigProperties = PluginConfigProperties()
        registry = PluginRegistry(listOf(pluginA), pluginConfigProperties)
        registry.initialize()

        // Initially not enabled
        assertTrue(registry.getEnabledPlugins(PluginPhase.PRE_ROUTE).isEmpty())

        // Enable it
        val config = mapOf<String, Any>("setting1" to "value1")
        val result = registry.enablePlugin("plugin-a", config)

        assertTrue(result)
        verify(exactly = 1) { pluginA.initialize(config) }
        assertEquals(1, registry.getEnabledPlugins(PluginPhase.PRE_ROUTE).size)
    }

    @Test
    fun `enablePlugin returns false for unknown plugin`() {
        pluginConfigProperties = PluginConfigProperties()
        registry = PluginRegistry(listOf(pluginA), pluginConfigProperties)
        registry.initialize()

        val result = registry.enablePlugin("nonexistent", emptyMap())
        assertFalse(result)
    }

    @Test
    fun `disablePlugin shuts down and removes plugin from enabled set`() {
        pluginConfigProperties = PluginConfigProperties(
            configs = mapOf("plugin-a" to PluginConfig(enabled = true))
        )
        registry = PluginRegistry(listOf(pluginA), pluginConfigProperties)
        registry.initialize()

        assertEquals(1, registry.getEnabledPlugins(PluginPhase.PRE_ROUTE).size)

        val result = registry.disablePlugin("plugin-a")

        assertTrue(result)
        verify(exactly = 1) { pluginA.shutdown() }
        assertTrue(registry.getEnabledPlugins(PluginPhase.PRE_ROUTE).isEmpty())
    }

    @Test
    fun `disablePlugin returns false for unknown plugin`() {
        pluginConfigProperties = PluginConfigProperties()
        registry = PluginRegistry(listOf(pluginA), pluginConfigProperties)
        registry.initialize()

        val result = registry.disablePlugin("nonexistent")
        assertFalse(result)
    }

    @Test
    fun `getPluginStatus returns status for all plugins`() {
        pluginConfigProperties = PluginConfigProperties(
            configs = mapOf(
                "plugin-a" to PluginConfig(enabled = true),
                "plugin-b" to PluginConfig(enabled = false)
            )
        )

        every { pluginB.isEnabled() } returns false

        registry = PluginRegistry(listOf(pluginA, pluginB), pluginConfigProperties)
        registry.initialize()

        val statuses = registry.getPluginStatus()
        assertEquals(2, statuses.size)

        val statusA = statuses.find { it.id == "plugin-a" }
        assertNotNull(statusA)
        assertTrue(statusA!!.enabled)
        assertTrue(statusA.healthy)

        val statusB = statuses.find { it.id == "plugin-b" }
        assertNotNull(statusB)
        assertFalse(statusB!!.enabled)
        assertFalse(statusB.healthy)
    }

    @Test
    fun `BOTH phase plugin appears in both PRE_ROUTE and POST_ROUTE`() {
        val bothPlugin = mockk<GatewayPlugin>(relaxed = true) {
            every { id } returns "plugin-both"
            every { name } returns "Both Plugin"
            every { version } returns "1.0.0"
            every { phase } returns PluginPhase.BOTH
            every { order } returns 15
            every { isEnabled() } returns true
            every { healthCheck() } returns Mono.just(PluginHealth(healthy = true))
        }

        pluginConfigProperties = PluginConfigProperties(
            configs = mapOf("plugin-both" to PluginConfig(enabled = true))
        )

        registry = PluginRegistry(listOf(bothPlugin), pluginConfigProperties)
        registry.initialize()

        assertEquals(1, registry.getEnabledPlugins(PluginPhase.PRE_ROUTE).size)
        assertEquals(1, registry.getEnabledPlugins(PluginPhase.POST_ROUTE).size)
    }
}
