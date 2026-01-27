package com.rokid.stream.receiver.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rokid.stream.receiver.ui.theme.RokidStreamTheme
import com.rokid.stream.receiver.util.LocaleManager

/**
 * Settings Activity with navigation for settings and language selection.
 */
class SettingsActivity : ComponentActivity() {
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            RokidStreamTheme {
                val navController = rememberNavController()
                
                NavHost(
                    navController = navController,
                    startDestination = "settings"
                ) {
                    composable("settings") {
                        SettingsScreen(
                            onNavigateToLanguage = {
                                navController.navigate("language")
                            },
                            onNavigateBack = {
                                finish()
                            }
                        )
                    }
                    
                    composable("language") {
                        LanguageSettingsScreen(
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}
