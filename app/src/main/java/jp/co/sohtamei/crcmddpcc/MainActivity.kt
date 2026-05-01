package jp.co.sohtamei.crcmddpcc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(vm)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: CameraViewModel) {
    val prefs = LocalContext.current.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
    var accepted by remember { mutableStateOf(prefs.getBoolean("eulaAccepted", false)) }
    if (!accepted) {
        EulaScreen {
            prefs.edit().putBoolean("eulaAccepted", true).apply()
            accepted = true
        }
    } else {
        ContentScreen(vm)
    }
}

@Composable
private fun EulaScreen(onAccept: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("EULA", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            text = "このアプリを使用してソニーのカメラを操作した場合、カメラはソニーのメーカー保証の対象外になります。\n\n" +
                "ご了承の上このアプリを使用して下さい。\n\n" +
                "このアプリはソニーのライブラリ Camera Remote Command を使用し、そーたメイが開発したものです。\n" +
                "また生命維持装置など重要な用途、軍事用途に利用することは出来ません。\n" +
                "このアプリによりカメラ故障等の損害が発生しても、そーたメイ、ソニーは損害に対する補償を行いません。",
            modifier = Modifier
                .weight(1f)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        )
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("Accept") }
    }
}

@Composable
private fun ContentScreen(vm: CameraViewModel) {
    val candidates = listOf(
        "D20D (Shutter_Speed)",
        "5007 (F_Number)",
        "5010 (Exposure_Bias_Compensation)",
        "D21E (ISO_Sensitivity)",
        "500E (Exposure_Program_Mode)",
    )
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Camera - DP,CC")

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            vm.requestUsbPermission()
                            vm.connectOrDisconnect()
                        },
                        enabled = vm.cameraName != "(none)",
                        //modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
                        //contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(if (vm.cameraStatus == "connected") "disconnect" else "connect")
                    }
                    Text("${vm.cameraName}${if (vm.cameraName == "(none)") "" else " - ${vm.cameraStatus}"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("LiveView")
                    Switch(checked = vm.isLiveview, onCheckedChange = { vm.toggleLiveview() }, enabled = vm.cameraStatus == "connected")
                    TextButton(onClick = vm::listcc, enabled = vm.cameraStatus == "connected") { Text("listCC") }
                    TextButton(onClick = vm::setupCamera, enabled = vm.cameraStatus == "connected") { Text("setup") }
                    TextButton(onClick = vm::capture, enabled = vm.cameraStatus == "connected") { Text("capture") }
                }

                if (vm.cameraStatus == "connected" && vm.isLiveview && vm.jpegBitmap != null) {
                    Image(
                        bitmap = vm.jpegBitmap!!,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://sohta02.web.fc2.com/camera_crCmdDPCC.html")))
                    }) {
                        Text("Visit SohtaMei")
                    }
                }

                var expanded by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = vm.codeHex,
                    onValueChange = { vm.codeHex = it.uppercase() },
                    label = { Text("code 0x") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    keyboardActions = KeyboardActions(onDone = { vm.updateDpcc(); expanded = false }),
                    singleLine = true,
                )
                Text(vm.describeDpcc(vm.codeHex))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "hide candidates" else "show candidates") }
                if (expanded) {
                    Column(modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))) {
                        candidates.forEachIndexed { index, item ->
                            Text(
                                item,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.codeHex = item.substringBefore(' ')
                                        vm.updateDpcc()
                                        expanded = false
                                    }
                                    .padding(12.dp)
                            )
                            if (index < candidates.lastIndex) HorizontalDivider()
                        }
                    }
                }

                OutlinedTextField(
                    value = vm.dpParams,
                    onValueChange = {},
                    label = { Text("DP/CC") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )

                if (vm.cameraStatus == "connected" && vm.modeInput != ModeInput.Disabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (vm.modeInput == ModeInput.DP) {
                            TextButton(onClick = { vm.setDp(TypeIncDec.Min) }) { Text("min") }
                            TextButton(onClick = { vm.setDp(TypeIncDec.Dec) }) { Text("dec") }
                            TextButton(onClick = { vm.setDp(TypeIncDec.Inc) }) { Text("inc") }
                            TextButton(onClick = { vm.setDp(TypeIncDec.Max) }) { Text("max") }
                        } else {
                            TextButton(onClick = { vm.setCc(2, 1) }) { Text("set(2->1)") }
                            TextButton(onClick = { vm.setCc(2) }) { Text("set(2)") }
                            TextButton(onClick = { vm.setCc(1) }) { Text("set(1)") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = vm.dpSetVal, onValueChange = { vm.dpSetVal = it }, label = { Text("value") }, modifier = Modifier.width(140.dp), singleLine = true)
                        TextButton(onClick = vm::setDpcc) { Text("set") }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Log", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(vm.logLines) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

