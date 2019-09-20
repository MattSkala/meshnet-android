package nl.tudelft.meshnet.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import nl.tudelft.meshnet.R

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }
}