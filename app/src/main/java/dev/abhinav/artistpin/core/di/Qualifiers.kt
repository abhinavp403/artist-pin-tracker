package dev.abhinav.artistpin.core.di

import org.koin.core.qualifier.named

val IoDispatcher = named("io")
val DefaultDispatcher = named("default")

/** The OkHttp client that carries the proxy key — only requests to our own backend may use it. */
val ProxyClient = named("proxyClient")
