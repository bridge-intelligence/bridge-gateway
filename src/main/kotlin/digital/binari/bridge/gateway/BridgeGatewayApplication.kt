package digital.binari.bridge.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BridgeGatewayApplication

fun main(args: Array<String>) {
    runApplication<BridgeGatewayApplication>(*args)
}
