package digital.binari.bridge.gateway.plugin

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "gateway.plugins")
data class PluginConfigProperties(
    val configs: Map<String, PluginConfig> = emptyMap()
)

data class PluginConfig(
    val enabled: Boolean = false,
    val order: Int = 100,
    val settings: Map<String, Any> = emptyMap()
)
