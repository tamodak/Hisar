package org.tamodak.hisar.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.tamodak.hisar.data.repository.CookieStore
import org.tamodak.hisar.web.GeckoEngine

/** The browser engine keeps the cookies; repositories reach them through [CookieStore]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds
    abstract fun cookieStore(engine: GeckoEngine): CookieStore
}
