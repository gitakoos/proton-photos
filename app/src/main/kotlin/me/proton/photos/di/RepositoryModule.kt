package me.proton.photos.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.photos.data.repository.DrivePhotoRepositoryImpl
import me.proton.photos.data.repository.LocalMediaRepositoryImpl
import me.proton.photos.data.repository.SyncStateRepositoryImpl
import me.proton.photos.domain.repository.DrivePhotoRepository
import me.proton.photos.domain.repository.LocalMediaRepository
import me.proton.photos.domain.repository.SyncStateRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindLocalMediaRepository(impl: LocalMediaRepositoryImpl): LocalMediaRepository

    @Binds
    @Singleton
    abstract fun bindDrivePhotoRepository(impl: DrivePhotoRepositoryImpl): DrivePhotoRepository

    @Binds
    @Singleton
    abstract fun bindSyncStateRepository(impl: SyncStateRepositoryImpl): SyncStateRepository
}
