package fr.amanin.demo

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import org.springframework.context.support.BeanDefinitionDsl
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.html
import org.springframework.web.reactive.function.server.router
import java.net.URI

fun BeanDefinitionDsl.configureIndex(websocket: Boolean, sse: Boolean) = bean {
    router {
        GET("/") { permanentRedirect(URI("index.html")).build() }
        GET("/index.html") { request ->
            val html = createHTML().html {
                head { title(content = "Demo websocket / SSE bridge") }
                body {
                    h1 { +"Spring Boot WebSocket/SSE bridge demo" }
                    if (websocket) {
                        h2 { +"Websocket" }
                        ul {
                            li {
                                a {
                                    href = request.uri().toString()
                                        .replace(Regex("^http(s)?:"), "ws$1:")
                                        .replace(Regex("index\\.html$"), "ws/datetime")
                                    +"Ask for current date and time"
                                }
                            }
                        }
                    }
                    if (sse) {
                        h2 { +"Server Sent Events" }
                        ul {
                            li {
                                a {
                                    href = request.uri().toString().replace(Regex("index\\.html$"), "sse/datetime")
                                    +"Ask for current date and time"
                                }
                            }
                        }
                    }
                }
            }
            ServerResponse.ok().html().bodyValue(html)
        }
    }
}
