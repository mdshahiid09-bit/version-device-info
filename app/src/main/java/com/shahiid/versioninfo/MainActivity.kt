package com.shahiid.versioninfo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.shahiid.versioninfo.ui.screens.HomeScreen
import com.shahiid.versioninfo.ui.theme.VersionDeviceInfoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VersionDeviceInfoTheme {
                HomeScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
