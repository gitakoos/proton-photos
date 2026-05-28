package eu.akoos.photos.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.network.domain.ApiClient
import eu.akoos.photos.data.api.PhotosApiClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {

    @Binds
    @Singleton
    abstract fun bindApiClient(impl: PhotosApiClient): ApiClient
}
