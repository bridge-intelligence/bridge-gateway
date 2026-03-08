package digital.binari.bridge.gateway.filter

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import digital.binari.bridge.gateway.config.JwtValidationProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
@EnableConfigurationProperties(JwtValidationProperties::class)
class JwksKeyProvider(
    private val jwtProps: JwtValidationProperties,
) {
    private val log = LoggerFactory.getLogger(JwksKeyProvider::class.java)

    private val keyCache = ConcurrentHashMap<String, RSAPublicKey>()
    private val lastRefresh = AtomicLong(0)

    fun getPublicKey(kid: String): RSAPublicKey? {
        keyCache[kid]?.let { return it }

        val now = System.currentTimeMillis()
        val elapsed = now - lastRefresh.get()
        if (elapsed < jwtProps.cacheRefreshSeconds * 1000) {
            return null
        }

        return try {
            refreshKeys()
            keyCache[kid]
        } catch (e: Exception) {
            log.error("Failed to fetch JWKS from {}: {}", jwtProps.jwksUrl, e.message)
            null
        }
    }

    @Synchronized
    private fun refreshKeys() {
        val now = System.currentTimeMillis()
        if (now - lastRefresh.get() < jwtProps.cacheRefreshSeconds * 1000 && keyCache.isNotEmpty()) {
            return
        }

        log.info("Refreshing JWKS from {}", jwtProps.jwksUrl)
        val jwkSet = JWKSet.load(URI(jwtProps.jwksUrl).toURL())
        val newKeys = ConcurrentHashMap<String, RSAPublicKey>()

        for (jwk in jwkSet.keys) {
            if (jwk is RSAKey) {
                val kid = jwk.keyID ?: continue
                newKeys[kid] = jwk.toRSAPublicKey()
                log.debug("Cached RSA public key: kid={}", kid)
            }
        }

        if (newKeys.isNotEmpty()) {
            keyCache.clear()
            keyCache.putAll(newKeys)
            lastRefresh.set(now)
            log.info("JWKS refreshed: {} keys cached", newKeys.size)
        } else {
            log.warn("JWKS response contained no RSA keys")
        }
    }
}
