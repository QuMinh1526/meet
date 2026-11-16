package com.example.sensitune

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything Shizuku-related: permission state, UserService binding and safe AIDL calls.
 * Every AIDL invocation is wrapped so a dead/denied service can never crash the UI.
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val REQUEST_CODE = 4001
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    interface Listener {
        fun onShizukuStateChanged(state: State)
        fun onServiceChanged(bound: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var initialized = false
    private lateinit var appContext: Context
    private var args: Shizuku.UserServiceArgs? = null
    private var connection: ServiceConnection? = null
    private var bindingInFlight = false
    private var heartbeatFailures = 0

    @Volatile
    var service: ISensiService? = null
        private set

    @Volatile
    var state: State = State.NOT_INSTALLED
        private set

    @Volatile
    var shizukuVersion: String = ""
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var serviceBound: Boolean = false
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) {
            refreshState()
            return
        }
        initialized = true
        LogBuffer.info(TAG, "init: registering Shizuku listeners")

        try {
            Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    LogBuffer.info(TAG, "Shizuku binder received")
                    refreshState()
                }
            })
            Shizuku.addBinderDeadListener(object : Shizuku.OnBinderDeadListener {
                override fun onBinderDead() {
                    LogBuffer.warn(TAG, "Shizuku binder died")
                    service = null
                    serviceBound = false
                    bindingInFlight = false
                    lastError = "Shizuku đã dừng"
                    refreshState()
                    notifyServiceChanged()
                }
            })
            Shizuku.addRequestPermissionResultListener(
                object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        LogBuffer.info(TAG, "permission result: request=$requestCode granted=$granted")
                        refreshState()
                        if (granted) bindService()
                    }
                }
            )
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "cannot register Shizuku listeners", t)
            lastError = "Không đăng ký được Shizuku listener: ${t.message}"
        }
        refreshState()
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
        mainHandler.post { l.onShizukuStateChanged(state) }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyState() {
        val s = state
        mainHandler.post {
            listeners.forEach {
                try {
                    it.onShizukuStateChanged(s)
                } catch (t: Throwable) {
                    LogBuffer.error(TAG, "listener state callback failed", t)
                }
            }
        }
    }

    private fun notifyServiceChanged() {
        val bound = serviceBound
        mainHandler.post {
            listeners.forEach {
                try {
                    it.onServiceChanged(bound)
                } catch (t: Throwable) {
                    LogBuffer.error(TAG, "listener service callback failed", t)
                }
            }
        }
    }

    fun refreshState() {
        val newState = detectState()
        if (newState != state) {
            LogBuffer.info(TAG, "Shizuku state: $state -> $newState")
        }
        state = newState
        shizukuVersion = try {
            if (Shizuku.pingBinder()) Shizuku.getVersion().toString() else ""
        } catch (t: Throwable) {
            ""
        }
        notifyState()
    }

    private fun detectState(): State {
        val installed = try {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (t: Throwable) {
            false
        }
        if (!installed) return State.NOT_INSTALLED
        val alive = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
        if (!alive) return State.NOT_RUNNING
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "checkSelfPermission failed", t)
            false
        }
        return if (granted) State.READY else State.NO_PERMISSION
    }

    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku chưa chạy"
                refreshState()
                return
            }
            if (Shizuku.isPreV11()) {
                lastError = "Phiên bản Shizuku quá cũ, cần Shizuku 11+"
                LogBuffer.warn(TAG, lastError!!)
                return
            }
            val rationale = try {
                Shizuku.shouldShowRequestPermissionRationale()
            } catch (t: Throwable) {
                false
            }
            LogBuffer.info(TAG, "requesting Shizuku permission, rationale=$rationale")
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            lastError = "Không xin được quyền Shizuku: ${t.message}"
            LogBuffer.error(TAG, "requestPermission failed", t)
        }
    }

    fun ensureBound() {
        refreshState()
        when (state) {
            State.READY -> if (service == null) bindService()
            State.NOT_INSTALLED -> lastError = "Chưa cài Shizuku"
            State.NOT_RUNNING -> lastError = "Shizuku chưa chạy"
            State.NO_PERMISSION -> lastError = "Chưa cấp quyền cho Shizuku"
        }
    }

    private fun buildArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, SensiUserService::class.java.name)
        )
            .daemon(false)
            .tag("SensiTune")
            .processNameSuffix("sensi")
            .version(BuildConfig.VERSION_CODE)
            .debuggable(BuildConfig.DEBUG)

    @Synchronized
    fun bindService() {
        if (service != null) {
            LogBuffer.info(TAG, "bindService: already bound")
            return
        }
        if (bindingInFlight) {
            LogBuffer.info(TAG, "bindService: already in flight")
            return
        }
        if (state != State.READY) {
            LogBuffer.warn(TAG, "bindService skipped, state=$state")
            return
        }

        val serviceArgs = args ?: buildArgs().also { args = it }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                LogBuffer.info(TAG, "UserService connected: $name")
                bindingInFlight = false
                if (binder == null) {
                    lastError = "Binder null khi kết nối UserService"
                    LogBuffer.error(TAG, lastError!!)
                    return
                }
                val iface = ISensiService.Stub.asInterface(binder)
                service = iface
                serviceBound = true
                try {
                    binder.linkToDeath({
                        LogBuffer.warn(TAG, "UserService binder died (linkToDeath)")
                        service = null
                        serviceBound = false
                        lastError = "UserService bị chết - hãy kết nối lại"
                        notifyServiceChanged()
                    }, 0)
                } catch (t: Throwable) {
                    LogBuffer.error(TAG, "linkToDeath failed", t)
                }
                try {
                    val nativeDir = appContext.applicationInfo.nativeLibraryDir
                    LogBuffer.info(TAG, "sending nativeLibraryDir=$nativeDir")
                    iface.setNativeLibraryPath(nativeDir)
                } catch (t: Throwable) {
                    LogBuffer.error(TAG, "setNativeLibraryPath failed", t)
                    lastError = "Không gửi được đường dẫn thư viện native: ${t.message}"
                }
                notifyServiceChanged()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                LogBuffer.warn(TAG, "UserService disconnected: $name")
                service = null
                serviceBound = false
                bindingInFlight = false
                lastError = "UserService bị ngắt - hãy kết nối lại"
                notifyServiceChanged()
            }
        }
        connection = conn
        try {
            bindingInFlight = true
            LogBuffer.info(TAG, "binding UserService (version=${BuildConfig.VERSION_CODE})")
            Shizuku.bindUserService(serviceArgs, conn)
        } catch (t: Throwable) {
            bindingInFlight = false
            lastError = "Không bind được UserService: ${t.message}"
            LogBuffer.error(TAG, "bindUserService failed", t)
        }
    }

    @Synchronized
    fun unbindService() {
        val a = args
        val c = connection
        if (a == null || c == null) return
        try {
            LogBuffer.info(TAG, "unbinding UserService")
            Shizuku.unbindUserService(a, c, true)
        } catch (t: Throwable) {
            LogBuffer.error(TAG, "unbindUserService failed", t)
        } finally {
            service = null
            serviceBound = false
            connection = null
            bindingInFlight = false
            notifyServiceChanged()
        }
    }

    // ------------------------------------------------------------------
    // Safe AIDL calls
    // ------------------------------------------------------------------

    private fun <T> call(tag: String, fallback: T, block: (ISensiService) -> T): T {
        val s = service
        if (s == null) {
            lastError = "Chưa kết nối tới SensiUserService"
            LogBuffer.warn(TAG, "$tag: $lastError")
            return fallback
        }
        return try {
            block(s)
        } catch (e: DeadObjectException) {
            lastError = "UserService đã chết khi gọi $tag - hãy kết nối lại"
            LogBuffer.error(TAG, lastError!!, e)
            service = null
            serviceBound = false
            notifyServiceChanged()
            fallback
        } catch (e: RemoteException) {
            lastError = "Lỗi binder khi gọi $tag: ${e.message}"
            LogBuffer.error(TAG, lastError!!, e)
            fallback
        } catch (e: SecurityException) {
            lastError = "Bị từ chối quyền khi gọi $tag: ${e.message}"
            LogBuffer.error(TAG, lastError!!, e)
            fallback
        } catch (t: Throwable) {
            lastError = "Lỗi không xác định khi gọi $tag: ${t.message}"
            LogBuffer.error(TAG, lastError!!, t)
            fallback
        }
    }

    fun setEnabled(enabled: Boolean): Boolean {
        LogBuffer.info(TAG, "setEnabled($enabled)")
        call("setEnabled", Unit) { it.setEnabled(enabled) }
        return service != null
    }

    fun setFactor(x: Float, y: Float): Boolean {
        call("setFactor", Unit) { it.setFactor(x, y) }
        return service != null
    }

    /** Display.ROTATION_0..3. Must be called off the main thread. */
    fun setRotation(rotation: Int): Boolean {
        call("setRotation", Unit) { it.setRotation(rotation) }
        return service != null
    }

    /** true keeps the grab but forwards touches unchanged (overlay gestures). */
    fun setScalingSuspended(suspended: Boolean): Boolean {
        call("setScalingSuspended", Unit) { it.setScalingSuspended(suspended) }
        return service != null
    }

    fun setEdgeMargin(percent: Int): Boolean {
        call("setEdgeMargin", Unit) { it.setEdgeMargin(percent) }
        return service != null
    }

    fun setSoftRecenter(enabled: Boolean): Boolean {
        call("setSoftRecenter", Unit) { it.setSoftRecenter(enabled) }
        return service != null
    }

    fun setReanchor(enabled: Boolean): Boolean {
        call("setReanchor", Unit) { it.setReanchor(enabled) }
        return service != null
    }

    fun heartbeat() {
        val s = service ?: return
        try {
            s.heartbeat()
            heartbeatFailures = 0
        } catch (t: Throwable) {
            heartbeatFailures++
            if (heartbeatFailures == 1 || heartbeatFailures % 5 == 0) {
                lastError = "Heartbeat thất bại (${heartbeatFailures}x): ${t.message}"
                LogBuffer.error(TAG, lastError!!, t)
            }
            if (t is DeadObjectException || t is RemoteException) {
                service = null
                serviceBound = false
                notifyServiceChanged()
            }
        }
    }

    fun emergencyRelease(reason: String = "app emergency button"): Boolean {
        LogBuffer.warn(TAG, "emergencyRelease: $reason")
        val s = service
        if (s == null) {
            lastError = "Không kết nối được service để nhả gấp (nếu app đã chết, grab đã tự nhả)"
            LogBuffer.error(TAG, lastError!!)
            return false
        }
        return try {
            s.emergencyRelease()
            true
        } catch (t: Throwable) {
            lastError = "Nhả cảm ứng thất bại: ${t.message}"
            LogBuffer.error(TAG, lastError!!, t)
            false
        }
    }

    fun statusJson(): String? = call("getStatus", null) { it.getStatus() }

    fun logsJson(sinceSeq: Int): String? = call("getLogs", null) { it.getLogs(sinceSeq) }

    fun diagnoseJson(): String? = call("diagnose", null) { it.diagnose() }

    /** Tells the shell process which HUD metrics are needed right now. */
    fun configurePerf(metricsCsv: String, foregroundPackage: String?) {
        call("configurePerf", Unit) {
            it.configurePerf(metricsCsv, foregroundPackage ?: "")
        }
    }

    /** One HUD sample; null when the UserService is not connected. */
    fun perfSnapshot(): String? = call("getPerfSnapshot", null) { it.getPerfSnapshot() }

    fun destroyService() {
        call("destroy", Unit) { it.destroy() }
    }
}
