package fr.amanin.demo

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.support.beans

@SpringBootApplication
class DemoApp

fun main(args: Array<String>) {
    runApplication<DemoApp>(*args) {
        addInitializers(
        beans {
            val enableWebsocket = "websocket" in env.activeProfiles
            val enableSSE = "sse" in env.activeProfiles
            if (enableWebsocket) configureWebSocket()
            if (enableSSE) configureSSE()
            configureIndex(enableWebsocket, enableSSE)
        },

        beans {
            bean {
                CommandLineRunner {

                    val port = env.getProperty("server.port", "8080").toInt()
                    println(
                        """
                
                        GO TO http://localhost:${port}/
                        
                        """.trimIndent()
                    )
                }
            }
        }
        )

    }
}
