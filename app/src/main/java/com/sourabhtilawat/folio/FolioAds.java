package com.sourabhtilawat.folio;

/*
 * Ads for the free version (Google AdMob). Folio Pro buyers never see ads.
 * Developed by Sourabh Tilawat.
 *
 * - A small adaptive banner under the app.
 * - A full-screen ad at most once every 3 finished jobs and never more than once per 2 minutes,
 *   shown only after a job is done (never at launch or in the middle of a task).
 * - Google's consent form (UMP) is shown first where the law requires it (EEA, UK, Switzerland).
 *
 * Ad IDs come from app/build.gradle (admob_* values). They are Google's test IDs until replaced.
 */

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;

public class FolioAds {
    private static final int JOBS_PER_AD = 3;
    private static final long MIN_GAP_MS = 120_000;

    private final Activity activity;
    private final FrameLayout slot;
    private final ConsentInformation consent;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean pro;
    private AdView banner;
    private InterstitialAd interstitial;
    private int jobs = 0;
    private long lastShown = 0;

    public FolioAds(Activity activity, FrameLayout slot, boolean pro) {
        this.activity = activity;
        this.slot = slot;
        this.pro = pro;
        this.consent = UserMessagingPlatform.getConsentInformation(activity);
        slot.setVisibility(View.GONE);
        if (pro) return;
        consent.requestConsentInfoUpdate(activity, new ConsentRequestParameters.Builder().build(),
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, err -> {
                    if (consent.canRequestAds()) startAds();
                }),
                err -> { });
        if (consent.canRequestAds()) startAds();
    }

    private String id(int res) { return activity.getString(res); }

    private void startAds() {
        if (pro || !started.compareAndSet(false, true)) return;
        new Thread(() -> MobileAds.initialize(activity, status -> activity.runOnUiThread(() -> {
            loadBanner();
            loadInterstitial();
        }))).start();
    }

    private void loadBanner() {
        if (pro || banner != null) return;
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int widthDp = (int) (dm.widthPixels / dm.density);
        banner = new AdView(activity);
        banner.setAdUnitId(id(R.string.admob_banner_id));
        banner.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp));
        slot.removeAllViews();
        slot.addView(banner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        slot.setVisibility(View.VISIBLE);
        banner.loadAd(new AdRequest.Builder().build());
    }

    private void loadInterstitial() {
        if (pro) return;
        InterstitialAd.load(activity, id(R.string.admob_interstitial_id), new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override public void onAdLoaded(InterstitialAd ad) { interstitial = ad; }
                    @Override public void onAdFailedToLoad(LoadAdError e) { interstitial = null; }
                });
    }

    private void maybeShowInterstitial() {
        if (pro) return;
        jobs++;
        long now = System.currentTimeMillis();
        final InterstitialAd ad = interstitial;
        if (ad == null || jobs % JOBS_PER_AD != 0 || now - lastShown < MIN_GAP_MS) return;
        interstitial = null;
        lastShown = now;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() { loadInterstitial(); }
            @Override public void onAdFailedToShowFullScreenContent(AdError e) { loadInterstitial(); }
        });
        ad.show(activity);
    }

    /** Called when Folio Pro is bought or restored: removes every ad at once. */
    public void setPro(boolean owned) {
        pro = owned;
        if (owned) {
            interstitial = null;
            if (banner != null) { banner.destroy(); banner = null; }
            slot.removeAllViews();
            slot.setVisibility(View.GONE);
        } else if (consent.canRequestAds()) {
            if (started.get()) { loadBanner(); loadInterstitial(); } else startAds();
        }
    }

    public void onPause() { if (banner != null) banner.pause(); }
    public void onResume() { if (banner != null) banner.resume(); }
    public void destroy() { if (banner != null) banner.destroy(); }

    /* ---------- JavaScript bridge: window.FolioAds ---------- */

    public final Bridge bridge = new Bridge();

    public class Bridge {
        @JavascriptInterface public void jobDone() { activity.runOnUiThread(FolioAds.this::maybeShowInterstitial); }

        @JavascriptInterface public boolean privacyRequired() {
            return consent.getPrivacyOptionsRequirementStatus() == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
        }

        @JavascriptInterface public void privacyOptions() {
            activity.runOnUiThread(() -> UserMessagingPlatform.showPrivacyOptionsForm(activity, err -> { }));
        }
    }
}
