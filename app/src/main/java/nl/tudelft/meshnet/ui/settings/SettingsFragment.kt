package nl.tudelft.meshnet.ui.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import nl.tudelft.meshnet.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }
}