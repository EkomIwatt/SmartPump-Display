// Application entry point. @HiltAndroidApp triggers Hilt's code generation for the whole app.
package app.balancee.smartpump.display

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SmartPumpApplication : Application()
