package dev.abhinav.artistpin.core.di

import org.koin.core.qualifier.named

val IoDispatcher = named("io")
val DefaultDispatcher = named("default")
