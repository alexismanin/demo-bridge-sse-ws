package fr.amanin.demo

import org.springframework.context.support.BeanDefinitionDsl
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono.just
import java.time.OffsetDateTime

fun BeanDefinitionDsl.configureWebSocket() = bean {
    SimpleUrlHandlerMapping(mapOf(
        "/ws/datetime" to WebSocketHandler { session -> handleDateTime(session) }
    ), -1)
}

fun handleDateTime(session: WebSocketSession) = session.send(
    session.receive()
           .map { msg -> msg.getPayloadAsText(Charsets.US_ASCII) }
           .flatMap { input ->
               if ("What time is it ?" == input) just(OffsetDateTime.now())
               else error(IllegalArgumentException("I do not understand"))
           }
        .map { datetime ->
            session.textMessage(datetime.toString())
        }
        .doFinally { println("WebSocket session closed") }
)
