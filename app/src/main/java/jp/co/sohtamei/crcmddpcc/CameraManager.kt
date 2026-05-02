package jp.co.sohtamei.crcmddpcc

import kotlin.coroutines.resume
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import android.util.Log
import androidx.core.content.ContextCompat

class CameraManager(private val context: Context) {
    private class IntRef(var value: Int)

    var onCameraNameChanged: ((String) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onDpUpdated: (() -> Unit)? = null

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val ioScope = CoroutineScope(Dispatchers.IO + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val txId = AtomicInteger(1)
    private val transportMutex = Mutex()

    private var cameraDevice: UsbDevice? = null
    private var usbInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var interruptIn: UsbEndpoint? = null
    private var connection: UsbDeviceConnection? = null
    private var eventJob: Job? = null
    private var receiverRegistered = false

    private var cameraStatus: String = ""
    private var dpParams: MutableList<Param> = mutableListOf()
    private var ccList: ByteArray = byteArrayOf()
    private var ptpOperationCb: ((Boolean) -> Unit)? = null
    private var ptpOperationDp: DPC = DPC.UNDEF

    companion object {
        const val ACTION_USB_PERMISSION = "jp.co.sohtamei.crcmddpcc.USB_PERMISSION"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val device = getUsbDeviceExtra(intent, UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        log("USB permission granted: ${device.productName ?: device.deviceName}")
                        cameraDevice = device
                        updateCameraName(device.productName ?: device.deviceName)
                        updateStatus("disconnected")
                    } else {
                        log("USB permission denied")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    scanForCamera()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getUsbDeviceExtra(intent, UsbManager.EXTRA_DEVICE)
                    if (device != null && device == cameraDevice) {
                        log("camera detached")
                        disconnectSequence(null)
                        cameraDevice = null
                        updateCameraName("(none)")
                        updateStatus("camera removed")
                    }
                }
            }
        }
    }

    init {
        registerReceiverCompat()
        scanForCamera()
        updateStatus("")
    }

    fun release() {
        kotlin.runCatching {
            if (receiverRegistered) {
                appContext.unregisterReceiver(receiver)
                receiverRegistered = false
            }
        }
        eventJob?.cancel()
        connection?.close()
    }

    private fun registerReceiverCompat() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        receiverRegistered = true
    }

    private fun getUsbDeviceExtra(intent: Intent, name: String): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(name)
        }
    }

    fun scanForCamera() {
        val device = usbManager.deviceList.values.firstOrNull { isLikelyPtpCamera(it) }
        if (device != null) {
            cameraDevice = device
            updateCameraName(device.productName ?: device.deviceName)
            updateStatus("disconnected")
            log("camera found: ${device.productName ?: device.deviceName}")
        } else {
            updateCameraName("(none)")
            updateStatus("idle")
            log("no USB PTP camera found")
        }
    }

    fun requestUsbPermission() {
        val device = cameraDevice ?: run {
            scanForCamera()
            cameraDevice ?: return log("requestUsbPermission(): no camera")
        }

        if (usbManager.hasPermission(device)) {
            updateCameraName(device.productName ?: device.deviceName)
            updateStatus("disconnected")
            return
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        val pi = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            flags
        )
        usbManager.requestPermission(device, pi)
    }

    fun connectSequence(completion: ((Boolean) -> Unit)?) {
        ioScope.launch {
            log("connectSequence(): begin")
            val ok = openSessionInternal() &&
                sendCommandInternal(PTP_OC.SDIO_Connect.raw, listOf(1, 0, 0), null) != null &&
                sendCommandInternal(PTP_OC.SDIO_Connect.raw, listOf(2, 0, 0), null) != null &&
                fetchExtDeviceInfo() &&
                sendCommandInternal(PTP_OC.SDIO_Connect.raw, listOf(3, 0, 0), null) != null
            mainHandler.post { completion?.invoke(ok) }
        }
    }

    fun disconnectSequence(completion: ((Boolean) -> Unit)?) {
        ioScope.launch {
            log("disconnectSequence(): begin")
            val ok = closeSessionInternal()
            mainHandler.post { completion?.invoke(ok) }
        }
    }

    fun liveview(completion: ((Boolean, ByteArray?) -> Unit)?) {
        ioScope.launch {
            val inData = sendCommandInternal(PTP_OC.GetObject.raw, listOf(0xFFFFC002.toInt()), null)
            if (inData == null || inData.size < 16) {
                mainHandler.post { completion?.invoke(false, null) }
                return@launch
            }
            val lvOffset = PtpParser.readUInt32LE(inData, 0).toInt()
            val lvSize = PtpParser.readUInt32LE(inData, 4).toInt()
            if (lvOffset + lvSize > inData.size) {
                mainHandler.post { completion?.invoke(false, null) }
                return@launch
            }
            mainHandler.post { completion?.invoke(true, inData.copyOfRange(lvOffset, lvOffset + lvSize)) }
        }
    }

    fun getDPCC(pcode: Int): Param? =
        dpParams.firstOrNull { it.pcode == pcode } ?: ccParams.firstOrNull { it.pcode == pcode }?.copy()

    fun listcc() {
        log("listcc")
        for (i in ccList.indices step 2) {
            if (i + 1 >= ccList.size) break
            val code = PtpParser.readUInt16LE(ccList, i)
            log("  %04X:%s".format(code, describeDpOrCc(code)))
        }
    }

    fun setupCamera(completion: ((Boolean) -> Unit)?) {
        ioScope.launch {
            log("setupCamera")
            val ok1 = setDpWait(DPC.Position_Key_Setting.raw, 1)
            if (ok1) log("  D25A:Position_Key_Setting=1(PC remote)")
            val ok2 = ok1 && setDpWait(DPC.Still_Image_Save_Destination.raw, 0x10)
            if (ok2) log("  D222:Still_Image_Save_Destination=0x10(camera)")
            val ok3 = ok2 && setDpWait(DPC.USB_Power_Supply.raw, 1)
            if (ok3) log("  D150:USB_Power_Supply=1(off)")
            mainHandler.post { completion?.invoke(ok3) }
        }
    }

    fun capture(completion: ((Boolean) -> Unit)?) {
        ioScope.launch {
            if (ptpOperationCb != null) {
                mainHandler.post { completion?.invoke(false) }
                return@launch
            }
            log("capture")
            val focusMode = getDPCC(DPC.Focus_Mode.raw)
            val ok = if (focusMode?.current == 0x0001L) {
                setDPCC(PTP_CC.S1_Button.raw, 2)
                performCaptureSequence()
            } else {
                setDPCC(PTP_CC.S1_Button.raw, 2)
                && waitForFocusIndication()
                && performCaptureSequence()
            }
            mainHandler.post { completion?.invoke(ok) }
        }
    }

    fun setDPCC(pcode: Int, value: Long): Boolean {
        val param = getDPCC(pcode) ?: return false
        val outData = encodeValue(param.datatype, value) ?: return false
        ioScope.launch {
            if (!param.ccDp) {
                if (param.modeRw != ModeRW.RW && dpParams.any { it.pcode == pcode }) return@launch
                sendCommandInternal(PTP_OC.SDIO_SetExtDevicePropValue.raw, listOf(pcode, 1), outData)
            } else {
                sendCommandInternal(PTP_OC.SDIO_ControlDevice.raw, listOf(pcode, 1), outData)
            }
        }
        return true
    }

    fun setDP(pcode: Int, type: TypeIncDec) {
        val param = getDPCC(pcode) ?: return
        if (param.ccDp) return
        val value = when (param.formflag) {
            Formflag.Range -> {
                if (param.enums.size != 3) return
                when (type) {
                    TypeIncDec.Inc -> (param.current + param.enums[2]).coerceAtMost(param.enums[1])
                    TypeIncDec.Dec -> (param.current - param.enums[2]).coerceAtLeast(param.enums[0])
                    TypeIncDec.Min -> param.enums[0]
                    TypeIncDec.Max -> param.enums[1]
                }
            }

            Formflag.Enum -> {
                if (param.enums.isEmpty()) return
                var index = param.currentIndex
                when (type) {
                    TypeIncDec.Inc -> index = (index + 1).coerceAtMost(param.enums.lastIndex)
                    TypeIncDec.Dec -> index = (index - 1).coerceAtLeast(0)
                    TypeIncDec.Min -> index = 0
                    TypeIncDec.Max -> index = param.enums.lastIndex
                }
                param.enums[index]
            }

            else -> return
        }
        setDPCC(pcode, value)
    }

    fun setCC(pcode: Int, value: Long) {
        setDPCC(pcode, value)
    }

    fun getAllDP() {
        ioScope.launch { getAllDPInternal() }
    }

    private suspend fun fetchExtDeviceInfo(): Boolean {
        val inData = sendCommandInternal(PTP_OC.SDIO_GetExtDeviceInfo.raw, listOf(0x012c, 1), null) ?: return false
        if (inData.size < 10) return false
        val dpSize = PtpParser.readUInt32LE(inData, 2).toInt()
        val ccSize = PtpParser.readUInt32LE(inData, 2 + 4 + dpSize * 2).toInt()
        val ccOffset = 2 + 4 + dpSize * 2 + 4
        if (ccOffset + ccSize > inData.size) return false
        ccList = inData.copyOfRange(ccOffset, ccOffset + ccSize)
        return true
    }

    private suspend fun openSessionInternal(): Boolean {
        val device = cameraDevice ?: return false.also { log("openSession(): no camera") }
        if (!usbManager.hasPermission(device)) return false.also { log("openSession(): no USB permission") }
        if (cameraStatus == "connected") return true

        val ptpInterface = device.interfaces().firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
            ?: device.interfaces().firstOrNull()
            ?: return false.also { log("openSession(): no interface") }

        val conn = usbManager.openDevice(device) ?: return false.also { log("openSession(): openDevice failed") }
        if (!conn.claimInterface(ptpInterface, true)) {
            conn.close()
            return false.also { log("openSession(): claimInterface failed") }
        }

        usbInterface = ptpInterface
        connection = conn
        bulkIn = ptpInterface.endpoints().firstOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        bulkOut = ptpInterface.endpoints().firstOrNull {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        interruptIn = ptpInterface.endpoints().firstOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
        }

        if (bulkIn == null || bulkOut == null) {
            conn.close()
            connection = null
            usbInterface = null
            return false.also { log("openSession(): missing bulk endpoints") }
        }

        startEventLoop()
        val response = sendCommandInternal(PTP_OC.OpenSession.raw, listOf(1), null)
        val ok = response != null
        if (ok) updateStatus("connected")
        return ok
    }

    private suspend fun closeSessionInternal(): Boolean {
        kotlin.runCatching { sendCommandInternal(PTP_OC.CloseSession.raw, emptyList(), null) }
        eventJob?.cancel()
        connection?.let { conn ->
            usbInterface?.let { conn.releaseInterface(it) }
            conn.close()
        }
        connection = null
        usbInterface = null
        bulkIn = null
        bulkOut = null
        interruptIn = null
        updateStatus("disconnected")
        return true
    }

    private fun startEventLoop() {
        eventJob?.cancel()
        val conn = connection ?: return
        val eventEp = interruptIn ?: return
        eventJob = ioScope.launch {
            val buf = ByteArray(eventEp.maxPacketSize.coerceAtLeast(32))
            while (true) {
                val n = conn.bulkTransfer(eventEp, buf, buf.size, 250)
                if (n > 0) handleEvent(buf.copyOf(n))
                delay(10)
            }
        }
    }

    private fun handleEvent(data: ByteArray) {
        runCatching {
            val c = PtpParser.parseContainer(data)
            if (PTPContainerType.fromRaw(c.type) != PTPContainerType.Event) return
            val event = PTP_SDIE.fromRaw(c.code)
            when (event) {
                PTP_SDIE.DevicePropChanged -> {
                    getAllDP()
                }

                else -> {
                    log("PTP EVENT: 0x%04X ${event?.name ?: "UNKNOWN"}".format(c.code))
                }
            }
        }
    }

    private suspend fun getAllDPInternal() {
        val inData = sendCommandInternal(PTP_OC.SDIO_GetAllExtDevicePropInfo.raw, listOf(1), null) ?: return
        val recvSize = inData.size
        var dp = 8
        while (dp < recvSize) {
            val pcode = PtpParser.readUInt16LE(inData, dp); dp += 2
            val datatype = PTP_DT.fromRaw(PtpParser.readUInt16LE(inData, dp)); dp += 2
            if (dp >= recvSize) break
            val getset = inData[dp].toInt() and 0xFF; dp += 1
            if (dp >= recvSize) break
            val isenabled = inData[dp].toInt() and 0xFF; dp += 1
            val dpRef = IntRef(dp)
            val factory = getVariableVal(datatype, inData, dpRef)
            val current = getVariableVal(datatype, inData, dpRef)
            dp = dpRef.value
            if (dp >= recvSize) break
            val formFlag = Formflag.fromRaw(inData[dp].toInt() and 0xFF); dp += 1

            val dpEnum = DPC.fromRaw(pcode)
            if (dpEnum != DPC.UNDEF) {
                val index = dpParams.indexOfFirst { it.pcode == pcode }.let { if (it >= 0) it else dpParams.size }
                if (index == dpParams.size) dpParams.add(Param(pcode = pcode))
                val updated = dpParams[index].current != current || dpParams[index].datatype != datatype
                if (updated) log("  %04X:%s=%d".format(pcode, dpEnum.name, current))
                dpParams[index].modeRw = when (isenabled) {
                    1 -> if (getset == 1) ModeRW.RW else ModeRW.R
                    2 -> ModeRW.R
                    else -> ModeRW.Invalid
                }
                dpParams[index].datatype = datatype
                dpParams[index].current = current
                dpParams[index].formflag = formFlag
                dpParams[index].enums.clear()
                dpParams[index].currentIndex = 0

                when (formFlag) {
                    Formflag.Range -> run {
                        val ref = IntRef(dp)
                        repeat(3) { dpParams[index].enums += getVariableVal(datatype, inData, ref) }
                        dp = ref.value
                    }

                    Formflag.Enum -> {
                        val skipNum = PtpParser.readUInt16LE(inData, dp); dp += 2
                        run {
                            val ref = IntRef(dp)
                            repeat(skipNum) { getVariableVal(datatype, inData, ref) }
                            dp = ref.value
                        }
                        val num = PtpParser.readUInt16LE(inData, dp); dp += 2
                        run {
                            val ref = IntRef(dp)
                            repeat(num) {
                                val v = getVariableVal(datatype, inData, ref)
                                if (v == current) dpParams[index].currentIndex = it
                                dpParams[index].enums += v
                            }
                            dp = ref.value
                        }
                    }

                    Formflag.None -> Unit
                }

                if (dpEnum == ptpOperationDp &&
                    (dpEnum != DPC.Focus_Indication || current == 0x02L || current == 0x06L)
                ) {
                    finishPtpOperation(true)
                }
            } else {
                when (formFlag) {
                    Formflag.Range -> run {
                        val ref = IntRef(dp)
                        repeat(3) { getVariableVal(datatype, inData, ref) }
                        dp = ref.value
                    }

                    Formflag.Enum -> {
                        val skipNum = PtpParser.readUInt16LE(inData, dp); dp += 2
                        run {
                            val ref = IntRef(dp)
                            repeat(skipNum) { getVariableVal(datatype, inData, ref) }
                            dp = ref.value
                        }
                        val num = PtpParser.readUInt16LE(inData, dp); dp += 2
                        run {
                            val ref = IntRef(dp)
                            repeat(num) { getVariableVal(datatype, inData, ref) }
                            dp = ref.value
                        }
                    }

                    Formflag.None -> Unit
                }
            }
        }
        mainHandler.post { onDpUpdated?.invoke() }
    }

    private suspend fun performCaptureSequence(): Boolean {
        val a = setDPCC(PTP_CC.S2_Button.raw, 2)
        delay(30)
        val b = setDPCC(PTP_CC.S2_Button.raw, 1)
        val c = setDPCC(PTP_CC.S1_Button.raw, 1)
        return a && b && c
    }

    private suspend fun waitForFocusIndication(): Boolean {
        ptpOperationDp = DPC.Focus_Indication
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            ptpOperationCb = { ok -> if (cont.isActive) cont.resume(ok, onCancellation = null) }
            ioScope.launch {
                repeat(30) {
                    delay(100)
                    getAllDPInternal()
                    if (ptpOperationCb == null) return@launch
                }
                if (ptpOperationCb != null) finishPtpOperation(false)
            }
        }
    }

    private suspend fun setDpWait(pcode: Int, value: Long): Boolean {
        val param = getDPCC(pcode) ?: return false
        if (param.current == value) return true
        ptpOperationDp = DPC.fromRaw(pcode)
        if (!setDPCC(pcode, value)) return false
        return waitForPropertyUpdate(ptpOperationDp)
    }

    private suspend fun waitForPropertyUpdate(dpc: DPC): Boolean {
        ptpOperationDp = dpc
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            ptpOperationCb = { ok -> if (cont.isActive) cont.resume(ok, onCancellation = null) }
            ioScope.launch {
                repeat(30) {
                    delay(100)
                    getAllDPInternal()
                    if (ptpOperationCb == null) return@launch
                }
                if (ptpOperationCb != null) finishPtpOperation(false)
            }
        }
    }

    private fun finishPtpOperation(result: Boolean) {
        val cb = ptpOperationCb
        ptpOperationCb = null
        mainHandler.post { cb?.invoke(result) }
    }

    private suspend fun sendCommandInternal(opCode: Int, params: List<Int>, outData: ByteArray?): ByteArray? =
        transportMutex.withLock {
            val conn = connection ?: return null
            val outEp = bulkOut ?: return null
            val inEp = bulkIn ?: return null
            val currentTx = txId.getAndIncrement()
            val cmd = PtpCommandBuilder.makeCommand(opCode, currentTx, params)
            if (conn.bulkTransfer(outEp, cmd, cmd.size, 2000) < 0) {
                log("PTP ERROR: command send failed")
                return null
            }
            if (outData != null) {
                val packet = PtpCommandBuilder.makeData(opCode, currentTx, outData)
                if (conn.bulkTransfer(outEp, packet, packet.size, 2000) < 0) {
                    log("PTP ERROR: data send failed")
                    return null
                }
            }
            var first = readContainer(conn, inEp) ?: return null
            var inData: ByteArray? = null
            val firstContainer = kotlin.runCatching { PtpParser.parseContainer(first) }.getOrElse {
                log("RECV PARSE ERROR: ${it.message}")
                return null
            }
            if (PTPContainerType.fromRaw(firstContainer.type) == PTPContainerType.Data) {
                inData = firstContainer.payload
                first = readContainer(conn, inEp) ?: return null
                kotlin.runCatching { PtpParser.parseContainer(first) }.getOrElse {
                    log("RECV PARSE ERROR: ${it.message}")
                    return null
                }
            }
            inData ?: ByteArray(0)
        }

    private fun readContainer(conn: UsbDeviceConnection, ep: UsbEndpoint): ByteArray? {
        val packet = ByteArray(ep.maxPacketSize.coerceAtLeast(512))
        var readNum = conn.bulkTransfer(ep, packet, packet.size, 5000)
        if (readNum <= 0) return null
        if (readNum < 4) return packet.copyOf(readNum)
        val expected = PtpParser.readUInt32LE(packet, 0).toInt()
        val output = ByteArray(expected)
        System.arraycopy(packet, 0, output, 0, minOf(readNum, expected))
        var copied = readNum
        while ((readNum % 512) == 0) {
            readNum = conn.bulkTransfer(ep, output, copied, expected - copied, 5000)
            if (readNum <= 0) break
            copied += readNum
        }
        return output.copyOf(minOf(copied, expected))
    }

    private fun encodeValue(type: PTP_DT, value: Long): ByteArray? = when (type) {
        PTP_DT.INT8, PTP_DT.UINT8 -> byteArrayOf(value.toByte())
        PTP_DT.INT16, PTP_DT.UINT16 -> ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
        PTP_DT.INT32, PTP_DT.UINT32 -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
        PTP_DT.INT64, PTP_DT.UINT64 -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
        else -> null
    }

    private fun getVariableVal(datatype: PTP_DT, data: ByteArray, dpRef: IntRef): Long {
        var dp = dpRef.value
        val v = when (datatype) {
            PTP_DT.INT8 -> data.getOrNull(dp)?.toLong() ?: 0L
            PTP_DT.UINT8 -> (data.getOrNull(dp)?.toInt() ?: 0).toLong()
            PTP_DT.INT16 -> if (dp + 1 < data.size) PtpParser.readUInt16LE(data, dp).toShort().toLong() else 0L
            PTP_DT.UINT16 -> if (dp + 1 < data.size) PtpParser.readUInt16LE(data, dp).toLong() else 0L
            PTP_DT.INT32 -> if (dp + 3 < data.size) PtpParser.readUInt32LE(data, dp).toInt().toLong() else 0L
            PTP_DT.UINT32 -> if (dp + 3 < data.size) PtpParser.readUInt32LE(data, dp) else 0L
            PTP_DT.INT64, PTP_DT.UINT64 -> if (dp + 7 < data.size) PtpParser.readUInt64LE(data, dp) else 0L
            PTP_DT.STR -> {
                val strLen = data.getOrNull(dp)?.toInt() ?: 0
                dp += 1 + strLen * 2
                0L
            }

            else -> 0L
        }
        dp += when (datatype) {
            PTP_DT.INT8, PTP_DT.UINT8 -> 1
            PTP_DT.INT16, PTP_DT.UINT16 -> 2
            PTP_DT.INT32, PTP_DT.UINT32 -> 4
            PTP_DT.INT64, PTP_DT.UINT64 -> 8
            PTP_DT.STR -> 0
            else -> 0
        }
        dpRef.value = dp
        return v
    }

    private fun isLikelyPtpCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return true
        return device.interfaces().any { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
    }

    private fun UsbDevice.interfaces(): List<UsbInterface> = (0 until interfaceCount).map { getInterface(it) }
    private fun UsbInterface.endpoints(): List<UsbEndpoint> = (0 until endpointCount).map { getEndpoint(it) }

    private fun updateStatus(text: String) {
        cameraStatus = text
        mainHandler.post { onStatusChanged?.invoke(text) }
    }

    private fun updateCameraName(text: String) {
        mainHandler.post { onCameraNameChanged?.invoke(text) }
    }

    private fun log(text: String) {
        mainHandler.post { onLog?.invoke(text) }
    }
}