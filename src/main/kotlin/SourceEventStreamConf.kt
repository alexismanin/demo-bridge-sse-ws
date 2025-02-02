package fr.amanin.demo

import org.reactivestreams.Publisher
import org.springframework.context.support.BeanDefinitionDsl
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router
import org.springframework.web.reactive.function.server.sse
import org.springframework.web.reactive.socket.HandshakeInfo
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.adapter.ReactorNettyWebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.netty.Connection
import reactor.netty.http.client.WebsocketClientSpec
import reactor.netty.http.websocket.WebsocketInbound
import reactor.netty.http.websocket.WebsocketOutbound
import java.net.URI
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

        GET("/custom/sse/datetime") { request ->
            val delay = request.queryParam("delay")
                .map { Duration.parse(it) }
                .orElse(1.seconds)
            val wsServerUrl = requireNotNull(env.getProperty("bridge.websocket.url")) { "Missing websocket server url property: 'bridge.websocket.url'" }

            val endpoint = UriComponentsBuilder.fromUriString(wsServerUrl)
                .pathSegment("ws", "datetime")
                .build()
                .toUri()
            val wsConnection = client.customExecute(endpoint) { session ->
                val sendStream = session.send(Flux.interval(delay.toJavaDuration()).map { session.textMessage("What time is it ?") })
                session.receive()
                    .map { it.getPayloadAsText(Charsets.US_ASCII) }
                    .mergeWith(sendStream.then(Mono.empty()))
            }

            ServerResponse.ok().sse()
                .body(wsConnection, String::class.java)
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

/**
 * I'd rather have:
 * ```kotlin
 * interface WebSocketHandler<P: Publisher<out Any>> {
 *     fun handle(session: WebSocketSession): P
 * }
 * ```
 */
fun interface CustomWebSocketHandler<out P: Publisher<out Any>> {
    fun handle(session: WebSocketSession): P
}

private fun toHttpHeaders(inbound: WebsocketInbound): org.springframework.http.HttpHeaders {
    val headers = org.springframework.http.HttpHeaders()
    inbound.headers().forEach { (key, value) ->
        headers.computeIfAbsent(key) { ArrayList() }.add(value)
    }
    return headers
}

/**
 * TODO; I would rather return P, but I do not find how to do so and still bind websocket session lifecycle to the returned publisher
 */
private fun <V, P: Publisher<out V>> ReactorNettyWebSocketClient.customExecute(url: URI, handler: CustomWebSocketHandler<P>) : Flux<V> {
    val request = httpClient
        // Hack: buildSpec() is private. Mock websocket spec for test purposes
        .websocket(WebsocketClientSpec.builder().build())
        .uri(url.toString())

        val initializeSession: (Connection) -> WebSocketSession = { c ->
            check(c is WebsocketInbound && c is WebsocketOutbound) { "HACK for test purposes failed" }
            val responseHeaders = toHttpHeaders(c)
            val protocol = responseHeaders.getFirst("Sec-WebSocket-Protocol")
            val info = HandshakeInfo(url, responseHeaders, Mono.empty(), protocol)
            val factory = NettyDataBufferFactory(c.alloc())
            ReactorNettyWebSocketSession(
                c, c, info, factory, getMaxFramePayloadLength()
            )
        }

        return Flux.usingWhen(
            request.connect().map { initializeSession(it) },
            handler::handle,
            // NOTE: should be HttpClientFinalizer.discard, but it is package-private. Mock it for test purposes
            WebSocketSession::close)
}