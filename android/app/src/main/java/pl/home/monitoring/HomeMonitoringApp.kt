package pl.home.monitoring

import android.app.Application

class HomeMonitoringApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
