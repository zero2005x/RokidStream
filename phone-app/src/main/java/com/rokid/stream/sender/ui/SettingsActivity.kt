package com.rokid.stream.sender.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rokid.stream.sender.ui.theme.RokidStreamTheme
import com.rokid.stream.sender.util.LocaleManager

/**
 * Settings Activity with navigation for settings and language selection.
 */
class SettingsActivity : ComponentActivity() {
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if we should navigate directly to a specific screen
        val navigateTo = intent.getStringExtra("navigate_to")
        
        setContent {
            RokidStreamTheme {
                val navController = rememberNavController()
                
                // Handle direct navigation
                LaunchedEffect(navigateTo) {
                    if (navigateTo == "logs") {
                        navController.navigate("logs")
                    }
                }
                
                NavHost(
                    navController = navController,
                    startDestination = "settings"
                ) {
                    composable("settings") {
                        SettingsScreen(
                            onNavigateToLanguage = {
                                navController.navigate("language")
                            },
                            onNavigateToLogs = {
                                navController.navigate("logs")
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
                    
                    composable("logs") {
                        LogManagerScreen(
                            onNavigateBack = {
                                // If navigated directly, finish the activity
                                if (navigateTo == "logs") {
                                    finish()
                                } else {
                                    navController.popBackStack()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
