package com.rix.vpn.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The language the app is showing, for code that has no composition.
 *
 * The screens read the choice from a composition local, but the services build
 * notifications and error text on their own threads, long after the screen that
 * started them is gone. They need the same answer, so the choice is mirrored
 * here by the activity, and read back through [l10n].
 */
object UiLanguage {

    @Volatile
    var en: Boolean = false
}

/**
 * `getString` in the app's language rather than the phone's.
 *
 * The resource resolver follows the device locale, so without this an app set
 * to Persian on an English phone would draw Persian screens and English
 * notifications -- and, worse, an app set to English on a Persian phone would
 * get Persian ones. Building the context with the app's locale makes the two
 * agree, and Persian is the default resources file, so it needs no suffix.
 */
fun Context.l10n(id: Int, vararg args: Any): String {
    val tag = if (UiLanguage.en) "en" else "fa"
    val config = Configuration(resources.configuration)
    config.setLocale(Locale(tag))
    return createConfigurationContext(config).getString(id, *args)
}