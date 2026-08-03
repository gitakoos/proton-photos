/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
 *
 * This file is part of Photos for Proton.
 *
 * Photos for Proton is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package eu.akoos.photos.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.data.api.NewsApi
import eu.akoos.photos.data.repository.NewsRepositoryImpl
import eu.akoos.photos.domain.repository.NewsRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * The news feed's network stack, its own client for the same reason the updater keeps one: website
 * traffic (no Proton headers, no pinning) never shares connection state with the Drive session.
 */
@Module
@InstallIn(SingletonComponent::class)
object NewsModule {

    private const val WEBSITE_BASE_URL = "https://www.photosforproton.eu/"

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L

    @Provides
    @Singleton
    @Named("Website")
    fun provideWebsiteOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "PhotosForProton/${BuildConfig.VERSION_NAME}")
                .build()
            chain.proceed(req)
        }
        .build()

    @Provides
    @Singleton
    fun provideNewsApi(@Named("Website") client: OkHttpClient): NewsApi {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(WEBSITE_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(NewsApi::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NewsRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindNewsRepository(impl: NewsRepositoryImpl): NewsRepository
}
