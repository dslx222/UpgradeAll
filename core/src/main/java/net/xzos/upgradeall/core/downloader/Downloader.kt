package net.xzos.upgradeall.core.downloader

import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.FetchGroup
import com.tonyodev.fetch2.Request
import com.tonyodev.fetch2.util.DEFAULT_GROUP_ID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import net.xzos.upgradeall.core.coreConfig
import net.xzos.upgradeall.core.log.ObjectTag
import net.xzos.upgradeall.core.log.ObjectTag.Companion.core
import net.xzos.upgradeall.core.utils.*
import net.xzos.upgradeall.core.utils.coroutines.CoroutinesCount
import net.xzos.upgradeall.core.utils.file.FileUtil
import net.xzos.upgradeall.core.utils.file.getFileByAutoRename
import net.xzos.upgradeall.core.utils.oberver.ObserverFun
import java.io.File


/* 下载管理 */
class Downloader internal constructor() {

    lateinit var downloadId: DownloadId
    val downloadDir = FileUtil.getNewRandomNameFile(FileUtil.DOWNLOAD_CACHE_DIR, true)

    internal val downloadOb = DownloadOb({}, {}, {},
            completeFunc = { completeObserverFun(it) },
            cancelFunc = { cancelObserverFun(it) }, {})

    private val fetch = runBlocking { DownloadService.getFetch() }

    private val requestList: MutableList<Request> = mutableListOf()
    private val completeObserverFun: ObserverFun<Download> = fun(_) {
        cancel()
    }

    private val cancelObserverFun: ObserverFun<Download> = fun(_) {
        delTask()
    }

    private fun register(downloadOb: DownloadOb) {
        DownloadRegister.registerOb(downloadId, downloadOb)
    }

    fun unregister(downloadOb: DownloadOb) {
        DownloadRegister.unRegisterByOb(downloadOb)
    }

    suspend fun getFileList(): List<File> {
        getFetchDownload(downloadId)?.run {
            return listOf(File(this.file))
        } ?: getFetchGroup(downloadId)?.run {
            return downloads.map { File(it.file) }
        } ?: return listOf()
    }

    fun removeFile() {
        delTask()
        downloadDir.delete()
    }

    internal fun addTask(
            fileName: String, url: String,
            headers: Map<String, String> = mapOf(), cookies: Map<String, String> = mapOf()
    ) {
        if (url.isNotBlank()) {
            val request = makeRequest(fileName, url, headers, cookies)
            requestList.add(request)
        }
    }

    internal suspend fun start(taskStartedFun: (Int) -> Unit, taskStartFailedFun: () -> Unit, vararg downloadOb: DownloadOb) {
        if (requestList.isEmpty()) {
            taskStartFailedFun()
            return
        }
        downloadId = if (requestList.size == 1)
            DownloadId(false, requestList[0].id)
        else
            DownloadId(true, groupId)
        var start = false
        val ended = CoroutinesCount(requestList.size)
        val mutex = Mutex()
        for (request in requestList) {
            request.groupId = downloadId.id
            fetch.enqueue(request, fun(request) {
                mutex.runWithLock {
                    if (start) return@runWithLock
                    start = true
                    downloadOb.forEach { register(it) }
                    register()
                    taskStartedFun(request.id)
                }
                ended.down()
            }, {
                taskStartFailedFun()
                ended.down()
            })
        }
        ended.waitNum(0)
    }

    internal fun resume() {
        if (downloadId.isGroup)
            fetch.resumeGroup(downloadId.id)
        else
            fetch.resume(downloadId.id)
    }

    internal fun pause() {
        if (downloadId.isGroup)
            fetch.pauseGroup(downloadId.id)
        else
            fetch.pause(downloadId.id)
    }

    suspend fun getDownloadProgress(): Int {
        getFetchDownload(downloadId)?.run {
            return progress
        } ?: getFetchGroup(downloadId)?.run {
            return groupDownloadProgress
        } ?: return -1
    }

    suspend fun getDownloadList(): List<Download> {
        getFetchDownload(downloadId)?.run {
            return listOf(this)
        } ?: getFetchGroup(downloadId)?.run {
            return this.downloads
        }
        return emptyList()
    }

    internal fun retry() {
        if (downloadId.isGroup) {
            fetch.getDownloadsInGroup(downloadId.id) {
                for (download in it) {
                    fetch.retry(download.id)
                }
            }
        } else
            fetch.retry(downloadId.id)
    }

    internal fun cancel() {
        if (downloadId.isGroup)
            fetch.cancelGroup(downloadId.id)
        else
            fetch.cancel(downloadId.id)
    }

    private fun delTask() {
        if (downloadId.isGroup)
            fetch.deleteGroup(downloadId.id)
        else
            fetch.delete(downloadId.id)
        downloadDir.delete()
        unregister()
    }

    private fun register() {
        DownloaderManager.addDownloader(this)
    }

    private fun unregister() {
        DownloaderManager.removeDownloader(this)
    }

    companion object {
        const val TAG = "Downloader"
        val logTagObject = ObjectTag(core, TAG)

        private val groupIdMutex = Mutex()

        private var groupId = DEFAULT_GROUP_ID + 1
            get() {
                return groupIdMutex.runWithLock {
                    field.also {
                        field += 1
                    }
                }
            }
    }

    private fun makeRequest(
            fileName: String, url: String,
            headers: Map<String, String> = mapOf(), cookies: Map<String, String> = mapOf()
    ): Request {
        // 检查重复任务
        val file = File(downloadDir, fileName).getFileByAutoRename()
        val filePath = file.path
        val request = Request(url, filePath)
        request.autoRetryMaxAttempts = coreConfig.download_auto_retry_max_attempts
        for ((key, value) in headers) {
            request.addHeader(key, value)
        }
        if (cookies.isNotEmpty()) {
            var cookiesStr = ""
            for ((key, value) in cookies) {
                cookiesStr += "$key: $value; "
            }
            if (cookiesStr.isNotBlank()) {
                cookiesStr = cookiesStr.subSequence(0, cookiesStr.length - 2).toString()
                request.addHeader("Cookie", cookiesStr)
            }
        }
        return request
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun getFetchDownload(downloadId: DownloadId): Download? {
        if (downloadId.isGroup) return null
        val valueLock = ValueLock<Download?>()
        fetch.getDownload(downloadId.id) {
            valueLock.value = it
        }
        return valueLock.value
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun getFetchGroup(downloadId: DownloadId): FetchGroup? {
        if (!downloadId.isGroup) return null
        val valueLock = ValueLock<FetchGroup>()
        fetch.getFetchGroup(downloadId.id) {
            valueLock.value = it
        }
        return valueLock.value
    }
}