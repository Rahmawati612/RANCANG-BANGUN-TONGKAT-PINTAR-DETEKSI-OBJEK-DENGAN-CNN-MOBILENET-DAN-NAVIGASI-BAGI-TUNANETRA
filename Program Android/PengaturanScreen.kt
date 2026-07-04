package com.skripsi.smart_cane

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PengaturanScreen(
    onBack: () -> Unit,
    bleViewModel: BleViewModel // Hubungan ke ViewModel Bluetooth utama
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Mengambil batas max asli dari HP Android (biasanya bernilai 15)
    val maxVolumeAsli = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // Simpan volume saat ini dalam bentuk angka index asli HP
    var volumeAsliSekarang by remember {
        mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    // Hitung konversi ke skala 100% untuk tampilan text UI
    val volumePersen = if (maxVolumeAsli > 0) {
        ((volumeAsliSekarang.toFloat() / maxVolumeAsli.toFloat()) * 100f).toInt()
    } else {
        0
    }

    // Mengambil data status real-time dari ESP32 melalui ViewModel
    val isScanning by bleViewModel.isScanning.collectAsState()
    val isConnected by bleViewModel.connected.collectAsState()
    val deviceName by bleViewModel.bleManager.deviceName.collectAsState()
    val jarakPeringatan by bleViewModel.thresholdCm.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFBBCEE4))
    ) {
        // Back Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF1A237E))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // ===== CARD STATUS PERANGKAT =====
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF212121)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Perangkat ESP32",
                            color = Color(0xFFAAAAAA),
                            fontSize = 11.sp
                        )
                        Text(
                            text = deviceName,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = if (isConnected) Color(0xFF4CAF50) else Color(0xFFFF5252),
                                shape = CircleShape
                            )
                    )
                }
            }

            // ===== TOMBOL SCAN BLE =====
            Button(
                onClick = { bleViewModel.toggleScan() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(bottom = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) Color(0xFFD32F2F) else Color(0xFF1565C0)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = if (isScanning) "⏹" else "🔵", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isScanning) "Stop Scanning" else "Scan Perangkat BLE",
                        fontSize = 15.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isScanning) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF212121)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF1565C0),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Mencari perangkat BLE...",
                            color = Color(0xFFAAAAAA),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ===== SLIDER JARAK PERINGATAN LIDAR =====
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF212121)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Jarak Threshold LiDAR",
                        color = Color(0xFFAAAAAA),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "${jarakPeringatan.toInt()} cm",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Slider(
                        value = jarakPeringatan,
                        onValueChange = { bleViewModel.updateThreshold(it) },
                        valueRange = 30f..400f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF1565C0),
                            activeTrackColor = Color(0xFF1565C0),
                            inactiveTrackColor = Color(0xFF444444)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("30 cm", color = Color(0xFF666666), fontSize = 11.sp)
                        Text("400 cm", color = Color(0xFF666666), fontSize = 11.sp)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF212121)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Volume Suara Asisten Audio",
                        color = Color(0xFFAAAAAA),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "$volumePersen / 100",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Slider(
                        value = volumeAsliSekarang.toFloat(),
                        onValueChange = {
                            val targetVolume = it.toInt()
                            volumeAsliSekarang = targetVolume
                            audioManager.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                targetVolume,
                                0
                            )
                        },
                        valueRange = 0f..maxVolumeAsli.toFloat(), // Skala internal tetap patuh batas max HP (0 - 15)
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF4CAF50),
                            activeTrackColor = Color(0xFF4CAF50),
                            inactiveTrackColor = Color(0xFF444444)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Mute", color = Color(0xFF666666), fontSize = 11.sp)
                        Text("Maksimal", color = Color(0xFF666666), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}