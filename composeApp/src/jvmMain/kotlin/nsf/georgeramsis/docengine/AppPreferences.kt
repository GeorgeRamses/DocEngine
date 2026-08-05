package nsf.georgeramsis.docengine

import java.util.prefs.Preferences

object AppPreferences {
    private val prefs: Preferences = Preferences.userRoot().node("nsf/georgeramsis/docengine")

    var isDarkTheme: Boolean
        get()      = prefs.getBoolean("isDarkTheme", true)
        set(value) = prefs.putBoolean("isDarkTheme", value)
}

