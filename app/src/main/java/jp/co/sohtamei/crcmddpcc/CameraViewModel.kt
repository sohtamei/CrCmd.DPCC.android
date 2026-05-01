package jp.co.sohtamei.crcmddpcc

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    var cameraName by mutableStateOf("(none)")
        private set
    var cameraStatus by mutableStateOf("idle")
        private set
    var jpegBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var isLiveview by mutableStateOf(false)
        private set
    var codeHex by mutableStateOf("5007")
    var dpParams by mutableStateOf("")
        private set
    var modeInput by mutableStateOf(ModeInput.Disabled)
        private set
    var dpSetVal by mutableStateOf("0")
    val logLines = mutableStateListOf<String>()

    private val manager = CameraManager(application.applicationContext)
    private var lvJob: Job? = null

    init {
        manager.onCameraNameChanged = { cameraName = it }
        manager.onStatusChanged = {
            cameraStatus = it
            if (it != "connected") stopTimer()
        }
        manager.onLog = { outputLog(it) }
        manager.onDpUpdated = { updateDpcc() }
    }

    override fun onCleared() {
        super.onCleared()
        manager.release()
    }

    fun requestUsbPermission() = manager.requestUsbPermission()
    fun rescan() = manager.scanForCamera()

    fun connectOrDisconnect() {
        if (cameraStatus == "connected") disconnect() else connect()
    }

    fun connect() {
        manager.connectSequence { result ->
            if (result) {
                cameraStatus = "connected"
                outputLog("connected")
                startTimer()
                manager.getAllDP()
            } else {
                disconnect()
            }
        }
    }

    fun disconnect() {
        manager.disconnectSequence {
            stopTimer()
            cameraStatus = "disconnected"
            outputLog("disconnected")
        }
    }

    fun toggleLiveview() {
        isLiveview = !isLiveview
    }

    private fun startTimer() {
        stopTimer()
        lvJob = viewModelScope.launch {
            while (true) {
                if (isLiveview) {
                    manager.liveview { result, data ->
                        if (result && data != null) {
                            BitmapFactory.decodeByteArray(data, 0, data.size)?.let {
                                jpegBitmap = it.asImageBitmap()
                            }
                        }
                    }
                }
                delay(50)
            }
        }
    }

    private fun stopTimer() {
        lvJob?.cancel()
        lvJob = null
    }

    fun updateDpcc() {
        val pcode = codeHex.toIntOrNull(16) ?: return
        val param = manager.getDPCC(pcode) ?: run {
            dpParams = ""
            modeInput = ModeInput.Disabled
            return
        }
        modeInput = when {
            param.ccDp -> ModeInput.CC
            param.datatype != PTP_DT.STR && param.modeRw == ModeRW.RW -> ModeInput.DP
            else -> ModeInput.Disabled
        }
        dpParams = if (param.ccDp) {
            "${param.formflag}=" + param.enums.joinToString(",")
        } else if (param.datatype == PTP_DT.STR) {
            "mode=${param.modeRw}"
        } else {
            "current=${param.current}(0x${param.current.toString(16).uppercase()})\n" +
                "mode=${param.modeRw}\n" +
                "${param.formflag}=" + param.enums.joinToString(",")
        }
    }

    fun setDpcc() {
        val pcode = codeHex.toIntOrNull(16) ?: return
        val parsed = if (dpSetVal.startsWith("0x") || dpSetVal.startsWith("0X")) {
            dpSetVal.drop(2).toLongOrNull(16)
        } else {
            dpSetVal.toLongOrNull()
        } ?: return
        manager.setDPCC(pcode, parsed)
    }

    fun setDp(type: TypeIncDec) {
        val pcode = codeHex.toIntOrNull(16) ?: return
        manager.setDP(pcode, type)
    }

    fun setCc(value: Long) {
        val pcode = codeHex.toIntOrNull(16) ?: return
        manager.setCC(pcode, value)
    }

    fun setCc(v1: Long, v2: Long) {
        val pcode = codeHex.toIntOrNull(16) ?: return
        manager.setCC(pcode, v1)
        viewModelScope.launch {
            delay(30)
            manager.setCC(pcode, v2)
        }
    }

    fun describeDpcc(codeStr: String): String = codeStr.toIntOrNull(16)?.let { describeDpOrCc(it) } ?: "(unknown)"
    fun listcc() = manager.listcc()
    fun setupCamera() = manager.setupCamera(null)
    fun capture() = manager.capture(null)

    private fun outputLog(line: String) {
        logLines += line
        if (logLines.size > 300) logLines.removeAt(0)
    }
}
