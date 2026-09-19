// Application entry point. @HiltAndroidApp triggers Hilt's code generation for the whole app.
//
// It also owns WorkManager's configuration from Phase 10f. That has to happen here rather than
// through the default initializer, because the upload worker is constructed by Hilt — it needs the
// repository, the API client and the credential store, none of which WorkManager's no-arg factory
// can supply. The manifest removes the default initializer to match; leaving both in place is the
// classic way to get an app that works in debug and cannot instantiate its worker in release.
package app.balancee.smartpump.display

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SmartPumpApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
