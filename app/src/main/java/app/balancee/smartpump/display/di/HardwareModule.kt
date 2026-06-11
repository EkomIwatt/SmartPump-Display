// Hilt module — provides PulseSource and RelayController, picking the real USB-serial
// implementation or the mock based on BuildConfig.MOCK_HARDWARE.
//
//   debug        → MOCK_HARDWARE = true  → mocks (the bulletproof demo / dev path)
//   debugRealHw  → MOCK_HARDWARE = false → real USB-serial driver (bench / live demo)
//   release      → MOCK_HARDWARE = false → real
//
// @Provides + Provider (not @Binds) so only the selected impl is instantiated — the unused one's
// dependencies (UsbManager / the relay graph) are never touched. The mock and real classes both
// compile into every variant; the flag decides at runtime.
package app.balancee.smartpump.display.di

import app.balancee.smartpump.display.BuildConfig
import app.balancee.smartpump.display.data.hardware.MockPulseSource
import app.balancee.smartpump.display.data.hardware.MockRelayController
import app.balancee.smartpump.display.data.hardware.UsbSerialPulseSource
import app.balancee.smartpump.display.data.hardware.UsbSerialRelayController
import app.balancee.smartpump.display.domain.hardware.PulseSource
import app.balancee.smartpump.display.domain.hardware.RelayController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HardwareModule {

    @Provides
    @Singleton
    fun providePulseSource(
        mock: Provider<MockPulseSource>,
        real: Provider<UsbSerialPulseSource>,
    ): PulseSource = if (BuildConfig.MOCK_HARDWARE) mock.get() else real.get()

    @Provides
    @Singleton
    fun provideRelayController(
        mock: Provider<MockRelayController>,
        real: Provider<UsbSerialRelayController>,
    ): RelayController = if (BuildConfig.MOCK_HARDWARE) mock.get() else real.get()
}
