/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import eu.akoos.photos.data.api.GitHubReleasesApi
import eu.akoos.photos.data.repository.UpdateCheckerRepositoryImpl
import eu.akoos.photos.domain.repository.UpdateCheckerRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * The self-updater's network stack, kept separate from the Proton-auth client so GitHub traffic
 * (no Proton headers, no DoH, no pinning) never shares connection state with the Drive session.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdaterModule {

    private const val GITHUB_BASE_URL = "https://api.github.com/"

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L

    @Provides
    @Singleton
    @Named("GitHub")
    fun provideGitHubOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // GitHub rate-limits anonymous requests without a User-Agent harder.
            val req = chain.request().newBuilder()
                .header("User-Agent", "PhotosForProton/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github+json")
                .build()
            chain.proceed(req)
        }
        .build()

    @Provides
    @Singleton
    fun provideGitHubReleasesApi(@Named("GitHub") client: OkHttpClient): GitHubReleasesApi {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(GITHUB_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(GitHubReleasesApi::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdaterRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindUpdateCheckerRepository(
        impl: UpdateCheckerRepositoryImpl,
    ): UpdateCheckerRepository
}
