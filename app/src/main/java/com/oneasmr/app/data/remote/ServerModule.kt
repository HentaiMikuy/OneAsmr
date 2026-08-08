package com.oneasmr.app.data.remote

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Task 24 DI: shared OkHttp client (bounded timeouts so unreachable servers
 * surface a clear error instead of hanging the UI) + the per-server session
 * + the gateway binding the login flow depends on.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServerModule {

    const val SERVER_HTTP = "server_http"

    @Provides
    @Singleton
    @Named(SERVER_HTTP)
    fun provideServerHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ServerAuthModule {

    @Binds
    @Singleton
    abstract fun bindServerAuthGateway(impl: ServerAuthRepository): ServerAuthGateway
}
