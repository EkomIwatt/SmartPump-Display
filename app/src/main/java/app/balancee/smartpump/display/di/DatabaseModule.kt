// Hilt module — provides Room DB, all three DAOs, and binds all three repository implementations.
package app.balancee.smartpump.display.di

import android.content.Context
import androidx.room.Room
import app.balancee.smartpump.display.data.db.DeviceConfigDao
import app.balancee.smartpump.display.data.db.PulseStateDao
import app.balancee.smartpump.display.data.db.SmartPumpDatabase
import app.balancee.smartpump.display.data.db.StationIdentityDao
import app.balancee.smartpump.display.data.db.TransactionDao
import app.balancee.smartpump.display.data.repository.DeviceConfigRepositoryImpl
import app.balancee.smartpump.display.data.repository.PulseRepositoryImpl
import app.balancee.smartpump.display.data.repository.StationIdentityRepositoryImpl
import app.balancee.smartpump.display.data.repository.TransactionRepositoryImpl
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.repository.PulseRepository
import app.balancee.smartpump.display.domain.repository.StationIdentityRepository
import app.balancee.smartpump.display.domain.repository.TransactionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SmartPumpDatabase =
        Room.databaseBuilder(context, SmartPumpDatabase::class.java, "smartpump.db")
            .fallbackToDestructiveMigration(dropAllTables = true) // TODO: replace with proper migrations before production
            .build()

    @Provides
    fun provideTransactionDao(db: SmartPumpDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideDeviceConfigDao(db: SmartPumpDatabase): DeviceConfigDao = db.deviceConfigDao()

    @Provides
    fun providePulseStateDao(db: SmartPumpDatabase): PulseStateDao = db.pulseStateDao()

    @Provides
    fun provideStationIdentityDao(db: SmartPumpDatabase): StationIdentityDao =
        db.stationIdentityDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindTransactionRepository(impl: TransactionRepositoryImpl): TransactionRepository

    @Binds @Singleton
    abstract fun bindDeviceConfigRepository(impl: DeviceConfigRepositoryImpl): DeviceConfigRepository

    @Binds @Singleton
    abstract fun bindPulseRepository(impl: PulseRepositoryImpl): PulseRepository

    @Binds @Singleton
    abstract fun bindStationIdentityRepository(impl: StationIdentityRepositoryImpl): StationIdentityRepository
}
