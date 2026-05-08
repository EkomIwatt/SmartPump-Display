// Hilt module — binds PulseSource and RelayController to their mock implementations.
// Real impls (USB serial driver + Arduino GPIO) replace these in a later phase; flip
// the bindings here once they exist, gated on BuildConfig.MOCK_HARDWARE if both must coexist.
package app.balancee.smartpump.display.di

import app.balancee.smartpump.display.data.hardware.MockPulseSource
import app.balancee.smartpump.display.data.hardware.MockRelayController
import app.balancee.smartpump.display.domain.hardware.PulseSource
import app.balancee.smartpump.display.domain.hardware.RelayController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HardwareModule {

    @Binds @Singleton
    abstract fun bindPulseSource(impl: MockPulseSource): PulseSource

    @Binds @Singleton
    abstract fun bindRelayController(impl: MockRelayController): RelayController
}
