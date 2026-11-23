package com.pickle.blackbox.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Thin wrapper around the AdMob SDK:
 *  - an anchored adaptive banner for the bottom of the screen
 *  - an interstitial with a cooldown, shown when recording stops / settings open
 *
 * All calls must happen on the main thread (they are made from activities).
 */
class AdManager(private val context: Context) {

    private var initialized = false
    private var interstitial: InterstitialAd? = null
    private var loading = false
    private var lastShownAt = 0L

    fun initialize() {
        if (initialized || !AdConfig.ADS_ENABLED) return
        initialized = true
        MobileAds.initialize(context) {
            Log.d(TAG, "MobileAds initialized")
        }
    }

    /** Attaches a fresh banner to [container]. Safe to call more than once. */
    fun createBanner(container: ViewGroup) {
        if (!AdConfig.ADS_ENABLED) return
        initialize()
        container.removeAllViews()

        val metrics = container.resources.displayMetrics
        val widthPx = if (container.width > 0) container.width else metrics.widthPixels
        val widthDp = (widthPx / metrics.density).toInt().coerceAtLeast(160)
        val adSize = try {
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
        } catch (t: Throwable) {
            Log.w(TAG, "Adaptive size failed, falling back to BANNER", t)
            AdSize.BANNER
        }

        val adView = AdView(context).apply {
            adUnitId = AdConfig.BANNER_UNIT_ID
            setAdSize(adSize)
        }
        container.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    fun preloadInterstitial() {
        if (!AdConfig.ADS_ENABLED) return
        if (loading || interstitial != null) return
        initialize()
        loading = true
        InterstitialAd.load(
            context,
            AdConfig.INTERSTITIAL_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loading = false
                    interstitial = ad
                    Log.d(TAG, "Interstitial loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    interstitial = null
                    Log.w(TAG, "Interstitial failed: " + error.message)
                }
            }
        )
    }

    /**
     * Shows the interstitial when one is ready and the cooldown has passed.
     * [onDismissed] runs in every case — including "no ad" and "failed to show" — so the
     * caller can continue its flow (open the gallery, show a dialog, …).
     */
    fun showInterstitial(activity: Activity, onDismissed: () -> Unit) {
        val ad = interstitial
        val now = SystemClock.elapsedRealtime()
        val coolingDown = now - lastShownAt < AdConfig.INTERSTITIAL_COOLDOWN_MS
        if (!AdConfig.ADS_ENABLED || ad == null || coolingDown) {
            preloadInterstitial()
            onDismissed()
            return
        }

        interstitial = null
        lastShownAt = now
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preloadInterstitial()
                onDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Interstitial show failed: " + error.message)
                preloadInterstitial()
                onDismissed()
            }
        }
        ad.show(activity)
    }

    companion object {
        private const val TAG = "AdManager"
    }
}
