package app.fitcoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.fitcoach.notify.Notifier
import app.fitcoach.ui.FitApp
import app.fitcoach.ui.FitTheme
import app.fitcoach.widget.refreshWidgets
import app.fitcoach.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        SyncWorker.schedulePeriodic(this)   // does nothing until the user has given consent
        setContent { FitTheme { FitApp() } }
    }

    override fun onResume() {
        super.onResume()
        Notifier.scheduleNext(this)
        CoroutineScope(Dispatchers.Default).launch { refreshWidgets(applicationContext) }
    }
}
