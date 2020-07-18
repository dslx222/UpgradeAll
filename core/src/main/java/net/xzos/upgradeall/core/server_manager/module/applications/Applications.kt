package net.xzos.upgradeall.core.server_manager.module.applications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.xzos.upgradeall.core.data.database.AppDatabase
import net.xzos.upgradeall.core.oberver.Informer
import net.xzos.upgradeall.core.server_manager.UpdateControl
import net.xzos.upgradeall.core.server_manager.UpdateManager
import net.xzos.upgradeall.core.server_manager.module.AppHub
import net.xzos.upgradeall.core.server_manager.module.BaseApp
import net.xzos.upgradeall.core.server_manager.module.app.App
import net.xzos.upgradeall.core.server_manager.module.app.Updater

class Applications(override val appDatabase: AppDatabase) : BaseApp, AppHub, Informer {

    val name = appDatabase.name
    private val applicationsUtils = ApplicationsUtils(appDatabase)

    val apps: MutableList<App> = applicationsUtils.apps
    private val excludeApps: MutableList<App> = applicationsUtils.excludeApps
    private val updateControl = UpdateControl(apps)
    private val updateStatus: Int get() = updateControl.getUpdateStatus()
    private var initData = false

    // 数据刷新锁
    private val appListMutex = Mutex()

    private suspend fun refreshAppList(updateControl: UpdateControl): UpdateControl {
        updateControl.renewAll(concurrency = false)
        val appMap = updateControl.appMap
        excludeInvalidApps(appMap[Updater.INVALID_APP]?.filterIsInstance<App>())
        includeValidApps(appMap[Updater.APP_OUTDATED]?.filterIsInstance<App>())
        includeValidApps(appMap[Updater.APP_LATEST]?.filterIsInstance<App>())
        updateControl.apps = apps
        return updateControl.also {
            notifyChanged()
        }
    }

    suspend fun getNeedUpdateAppList(block: Boolean = true): List<App> {
        return updateControl.getNeedUpdateAppList(block = block).filterIsInstance<App>()
    }

    override suspend fun getUpdateStatus(): Int {
        refreshAppList(updateControl)
        if (!initData) {
            initData = true
            GlobalScope.launch(Dispatchers.IO) {
                refreshAppList(UpdateControl(excludeApps))
            }
        }
        return updateStatus
    }

    override suspend fun getAppUpdateStatus(baseApp: BaseApp): Int {
        return updateControl.refreshAppUpdate(baseApp).also {
            if (it.second) {
                UpdateManager.refreshAppUpdate(this)
                UpdateManager.notifyChanged()
            }
        }.first
    }

    private suspend fun includeValidApps(appList: List<App>?) {
        if (appList.isNullOrEmpty()) return
        val invalidPackageName = appDatabase.extraData?.applicationsConfig?.invalidPackageName
        appListMutex.withLock {
            for (app in appList) {
                if (app in excludeApps) {
                    apps.add(app)
                    excludeApps.remove(app)
                    app.appDatabase.targetChecker?.extraString?.let { packageName ->
                        invalidPackageName?.remove(packageName)
                    }
                }
            }
        }
        appDatabase.save(false)
    }

    private suspend fun excludeInvalidApps(appList: List<App>?) {
        if (appList.isNullOrEmpty()) return
        val invalidPackageName = appDatabase.extraData?.applicationsConfig?.invalidPackageName
        appListMutex.withLock {
            for (app in appList) {
                if (app in apps) {
                    apps.remove(app)
                    excludeApps.add(app)
                    app.appDatabase.targetChecker?.extraString?.let { packageName ->
                        invalidPackageName?.add(packageName)
                    }
                }
            }
        }
        appDatabase.save(false)
    }
}
