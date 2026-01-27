package com.rokid.stream.sender.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rokid.stream.sender.R
import com.rokid.stream.sender.util.LocaleManager

/**
 * Language Selection Activity
 * 
 * Allows users to select the app language.
 * The selected language is also synced to glasses via BLE.
 */
class LanguageSelectionActivity : AppCompatActivity() {
    
    private lateinit var rvLanguages: RecyclerView
    private lateinit var tvCurrentLanguage: TextView
    private var currentLanguage: LocaleManager.AppLanguage = LocaleManager.AppLanguage.ENGLISH
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_selection)
        
        // Initialize views
        rvLanguages = findViewById(R.id.rv_languages)
        tvCurrentLanguage = findViewById(R.id.tv_current_language)
        
        // Get current language
        currentLanguage = LocaleManager.getSavedAppLanguage(this)
        tvCurrentLanguage.text = currentLanguage.nativeName
        
        // Setup back button
        findViewById<View>(R.id.btn_back).setOnClickListener {
            finish()
        }
        
        // Setup language list
        rvLanguages.layoutManager = LinearLayoutManager(this)
        rvLanguages.adapter = LanguageAdapter(
            languages = LocaleManager.getAvailableLanguages(),
            selectedLanguage = currentLanguage,
            onLanguageSelected = { language ->
                if (language != currentLanguage) {
                    LocaleManager.updateLocale(this, language.code)
                }
            }
        )
    }
    
    /**
     * Adapter for language list
     */
    private class LanguageAdapter(
        private val languages: List<LocaleManager.AppLanguage>,
        private val selectedLanguage: LocaleManager.AppLanguage,
        private val onLanguageSelected: (LocaleManager.AppLanguage) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.ViewHolder>() {
        
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvLanguageName: TextView = view.findViewById(R.id.tv_language_name)
            val ivSelected: ImageView = view.findViewById(R.id.iv_selected)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_language, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val language = languages[position]
            holder.tvLanguageName.text = language.nativeName
            holder.ivSelected.visibility = if (language == selectedLanguage) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                onLanguageSelected(language)
            }
        }
        
        override fun getItemCount() = languages.size
    }
}
