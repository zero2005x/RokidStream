package com.rokid.stream.sender.ui

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rokid.stream.sender.R
import com.rokid.stream.sender.util.LocaleManager

/**
 * Data class representing a language option.
 */
data class LanguageOption(
    val code: String,
    val nativeName: String,
    val englishName: String
)

/**
 * Composable screen for language selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    var selectedLanguage by remember {
        mutableStateOf(LocaleManager.getSavedLanguage(context))
    }
    
    val systemDefaultText = stringResource(R.string.lang_system)

    // Language options with native names
    val languages = remember(systemDefaultText) {
        listOf(
            LanguageOption(LocaleManager.SYSTEM_DEFAULT, systemDefaultText, "System Default"),
            LanguageOption("en", "English", "English"),
            LanguageOption("zh-CN", "简体中文", "Simplified Chinese"),
            LanguageOption("zh-TW", "繁體中文", "Traditional Chinese"),
            LanguageOption("ja", "日本語", "Japanese"),
            LanguageOption("ko", "한국어", "Korean"),
            LanguageOption("vi", "Tiếng Việt", "Vietnamese"),
            LanguageOption("th", "ไทย", "Thai"),
            LanguageOption("fr", "Français", "French"),
            LanguageOption("es", "Español", "Spanish"),
            LanguageOption("ru", "Русский", "Russian"),
            LanguageOption("uk", "Українська", "Ukrainian"),
            LanguageOption("ar", "العربية", "Arabic"),
            LanguageOption("it", "Italiano", "Italian")
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_language)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(languages) { language ->
                LanguageItem(
                    language = language,
                    isSelected = selectedLanguage == language.code,
                    onClick = {
                        if (selectedLanguage != language.code) {
                            selectedLanguage = language.code
                            activity?.let {
                                LocaleManager.updateLocale(it, language.code)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LanguageItem(
    language: LanguageOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = language.nativeName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (language.code != LocaleManager.SYSTEM_DEFAULT && language.nativeName != language.englishName) {
                    Text(
                        text = language.englishName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * Settings screen with language option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToLanguage: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Language Setting
            SettingsItem(
                title = stringResource(R.string.settings_language),
                subtitle = stringResource(R.string.settings_language_desc),
                onClick = onNavigateToLanguage
            )
            
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            
            // Log Manager Setting
            SettingsItem(
                title = stringResource(R.string.settings_logs),
                subtitle = stringResource(R.string.settings_logs_desc),
                onClick = onNavigateToLogs
            )
            
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            
            // About Section
            SettingsItem(
                title = stringResource(R.string.settings_about),
                subtitle = "${stringResource(R.string.settings_version)}: 1.0.0",
                onClick = { /* Show about dialog */ }
            )
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
