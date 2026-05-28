package eu.akoos.photos.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.akoos.photos.data.repository.DrivePhotoRepositoryImpl
import eu.akoos.photos.data.repository.LocalMediaRepositoryImpl
import eu.akoos.photos.data.repository.SyncStateRepositoryImpl
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
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
