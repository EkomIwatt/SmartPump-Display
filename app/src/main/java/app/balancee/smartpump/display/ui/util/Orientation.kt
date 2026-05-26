// Orientation helper for the now-rotatable kiosk (manifest screenOrientation="fullSensor").
// Screens are designed landscape-first and lay panes out side-by-side; in portrait they
// branch on isPortrait() to stack those panes vertically instead.
//
// Layouts that stack must keep their height BOUNDED — do not combine the stacked Column
// with verticalScroll while panes still use weight()/fillMaxHeight(), or Compose measures
// them against an infinite height constraint and crashes. The kiosk screens stay bounded
// (each pane takes an equal weight of the available height) so the existing pane internals
// keep working unchanged.
package app.balancee.smartpump.display.ui.util

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** True when the current viewport is portrait. Recomposes on device rotation. */
@Composable
fun isPortrait(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
