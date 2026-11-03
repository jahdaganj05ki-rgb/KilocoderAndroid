package com.kilocoder.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kilocoder.android.ui.AppRoot
import com.kilocoder.android.ui.theme.KiloCoderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KiloCoderApp).container
        setContent {
            KiloCoderTheme {
                AppRoot(container)
            }
        }
    }
}
