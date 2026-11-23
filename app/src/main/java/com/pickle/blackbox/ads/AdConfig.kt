package com.pickle.blackbox.ads

/**
 * AdMob placement configuration.
 *
 * ============================ READ ME ============================
 * Every ID below is Google's official **test** ID. They always fill, never earn money
 * and are safe to ship in a debug build. Before publishing:
 *
 *  1. Create an AdMob account and an app — https://apps.admob.com
 *  2. Copy the "App ID" (format ca-app-pub-XXXXXXXX~XXXXXXXXXX) into
 *     app/src/main/AndroidManifest.xml  ->  com.google.android.gms.ads.APPLICATION_ID
 *  3. Create two ad units (Banner, Interstitial) and paste their unit IDs below.
 *  4. Host app-ads.txt (see docs/app-ads.txt) on the developer website declared on
 *     your Play Store listing, and add the AdMob "app-ads.txt" verification.
 * ================================================================
 */
object AdConfig {

    // TODO(AdMob): replace with your real Banner ad unit ID.
    const val BANNER_UNIT_ID: String = "ca-app-pub-3940256099942544/6300978111"

    // TODO(AdMob): replace with your real Interstitial ad unit ID.
    const val INTERSTITIAL_UNIT_ID: String = "ca-app-pub-3940256099942544/1033173712"

    /** Interstitials are shown at most once per this interval. */
    const val INTERSTITIAL_COOLDOWN_MS: Long = 3L * 60L * 1000L

    /** Set to false temporarily if you need an ad-free build while testing cameras. */
    const val ADS_ENABLED: Boolean = true
}
