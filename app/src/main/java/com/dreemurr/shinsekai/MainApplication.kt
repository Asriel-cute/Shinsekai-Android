package com.dreemurr.shinsekai

import android.app.Application
import com.dreemurr.shinsekai.di.AppContainer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class MainApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 两个仓库并行加载，全部完成后置 ready。
        container.applicationScope.launch {
            coroutineScope {
                launch { runCatching { container.characterRepository.load() }.onFailure { it.printStackTrace() } }
                launch { runCatching { container.sessionRepository.load() }.onFailure { it.printStackTrace() } }
            }
            container.markReady()
        }
    }
}
