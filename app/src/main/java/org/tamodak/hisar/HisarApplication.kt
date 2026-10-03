package org.tamodak.hisar

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import dagger.hilt.android.HiltAndroidApp
import org.tamodak.hisar.data.repository.FilterRepository
import org.tamodak.hisar.vision.ImageFilter
import javax.inject.Inject

@HiltAndroidApp
class HisarApplication : Application() {
    @Inject lateinit var filters: FilterRepository
    @Inject lateinit var images: ImageFilter

    override fun onCreate() {
        super.onCreate()
        // The browser engine runs pages in processes of their own, which start this class too;
        // only the app's main process uses the lists.
        if (!isMainProcess()) return
        // The lists take a moment to read; start now so they are ready by the first request.
        filters.preload()
        images.preload()
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return name == null || name == packageName
    }
}
