package fr.amanin.demo

import org.springframework.context.support.BeanDefinitionDsl
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router
import org.springframework.web.reactive.function.server.sse
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

fun BeanDefinitionDsl.configureSSE() = bean {
    router {
        val client = ReactorNettyWebSocketClient()
        GET("/sse/datetime") { request ->
            val delay = request.queryParam("delay")
                .map { Duration.parse(it) }
                .orElse(1.seconds)
            val wsServerUrl = requireNotNull(env.getProperty("bridge.websocket.url")) { "Missing websocket server url property: 'bridge.websocket.url'" }

            val bridge = Sinks.many().unicast().onBackpressureBuffer<String>()
            val endpoint = UriComponentsBuilder.fromUriString(wsServerUrl)
                .pathSegment("ws", "datetime")
                .build()
                .toUri()
            val wsConnection = client.execute(endpoint) { session ->
                val send = session.send(Flux.interval(delay.toJavaDuration()).map { session.textMessage("What time is it ?") })
                val receive = session.receive()
                    .map { it.getPayloadAsText(Charsets.US_ASCII) }
                    .doOnNext { datetime -> bridge.emitNext(datetime, Sinks.EmitFailureHandler.busyLooping(delay.toJavaDuration())) }
                send.zipWith(receive.then())
                    .then()
            }

            ServerResponse.ok().sse()
                .body(bind(wsConnection, bridge), String::class.java)
        }
    }
}

private fun <V> bind(upstream: Mono<Void>, downstream: Sinks.Many<V>) : Flux<V> {
    val wsHandle = upstream.subscribe(
        {},
        { err -> downstream.tryEmitError(err)},
        { downstream.emitComplete(Sinks.EmitFailureHandler.busyLooping(2.seconds.toJavaDuration())) }
    )
    return downstream.asFlux().doFinally { wsHandle.dispose() ; println(downstream.tryEmitComplete()) }
}