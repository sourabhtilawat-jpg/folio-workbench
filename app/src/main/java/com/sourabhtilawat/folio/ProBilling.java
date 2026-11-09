package com.sourabhtilawat.folio;

/*
 * Folio Pro: one-time lifetime unlock sold through Google Play Billing (Billing Library 8).
 * Developed by Sourabh Tilawat.
 *
 * Product: "folio_pro_lifetime" (one-time product, created in Play Console > Monetize with Play > Products).
 * Ownership is cached on the device so Pro works offline, and re-checked with Google Play on every start,
 * so a refunded purchase is removed again.
 */

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

public class ProBilling implements PurchasesUpdatedListener {
    public static final String PRODUCT_ID = "folio_pro_lifetime";

    public interface Notifier { void send(String json); }

    private final Activity activity;
    private final Notifier notifier;
    private final SharedPreferences prefs;
    private final BillingClient client;
    private volatile ProductDetails details;
    private volatile boolean connecting = false;
    private volatile Runnable queued;

    public ProBilling(Activity activity, Notifier notifier) {
        this.activity = activity;
        this.notifier = notifier;
        this.prefs = activity.getSharedPreferences("folio_pro", Context.MODE_PRIVATE);
        this.client = BillingClient.newBuilder(activity)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build();
        connect(null);
    }

    /* ---------- state ---------- */

    public boolean isOwned() { return prefs.getBoolean("owned", false); }

    private void setOwned(boolean owned) { prefs.edit().putBoolean("owned", owned).apply(); }

    private String price() {
        String p = prefs.getString("price", "");
        return p == null ? "" : p;
    }

    private void sendState(String event, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("owned", isOwned());
            o.put("price", price());
            if (event != null) o.put("event", event);
            if (message != null) o.put("message", message);
            notifier.send(o.toString());
        } catch (Exception ignored) { }
    }

    /* ---------- connection ---------- */

    private void connect(final Runnable then) {
        if (client.isReady()) { if (then != null) then.run(); return; }
        if (then != null) queued = then;
        if (connecting) return;
        connecting = true;
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                connecting = false;
                Runnable q = queued;
                queued = null;
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    loadDetails();
                    checkPurchases(false);
                    if (q != null) q.run();
                } else if (q != null) {
                    sendState("error", "Google Play isn't available right now. Check your connection and try again.");
                }
            }

            @Override
            public void onBillingServiceDisconnected() { connecting = false; queued = null; }
        });
    }

    private void loadDetails() {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()))
                .build();
        client.queryProductDetailsAsync(params, (result, res) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK || res == null) return;
            List<ProductDetails> list = res.getProductDetailsList();
            if (list == null || list.isEmpty()) return;
            details = list.get(0);
            ProductDetails.OneTimePurchaseOfferDetails offer = details.getOneTimePurchaseOfferDetails();
            if (offer != null) {
                prefs.edit().putString("price", offer.getFormattedPrice()).apply();
                sendState(null, null);
            }
        });
    }

    /** Asks Google Play which purchases this account owns. Only a successful answer can remove Pro. */
    private void checkPurchases(final boolean userAsked) {
        client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (result, purchases) -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        if (userAsked) sendState("error", "Couldn't reach Google Play. Try again in a moment.");
                        return;
                    }
                    boolean owned = false;
                    if (purchases != null) for (Purchase p : purchases) if (handle(p)) owned = true;
                    setOwned(owned);
                    sendState(userAsked ? "restored" : null, null);
                });
    }

    /** Returns true when the purchase grants Pro. Acknowledges new purchases (Google refunds them after 3 days otherwise). */
    private boolean handle(Purchase p) {
        if (!p.getProducts().contains(PRODUCT_ID)) return false;
        if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return false;
        if (!p.isAcknowledged()) {
            client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(),
                    r -> { });
        }
        return true;
    }

    /* ---------- purchase flow ---------- */

    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            boolean owned = false, pending = false;
            for (Purchase p : purchases) {
                if (handle(p)) owned = true;
                else if (p.getProducts().contains(PRODUCT_ID) && p.getPurchaseState() == Purchase.PurchaseState.PENDING) pending = true;
            }
            if (owned) { setOwned(true); sendState("purchased", null); }
            else if (pending) sendState("pending", null);
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            checkPurchases(true);
        } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            sendState(null, null);
        } else {
            sendState("error", "The purchase didn't go through. You haven't been charged.");
        }
    }

    private void launch() {
        final ProductDetails d = details;
        if (d == null) {
            loadDetails();
            sendState("error", "Folio Pro isn't available yet. Please try again shortly.");
            return;
        }
        activity.runOnUiThread(() -> {
            BillingFlowParams params = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(
                            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
                    .build();
            client.launchBillingFlow(activity, params);
        });
    }

    public void onResume() { if (client.isReady()) checkPurchases(false); else connect(null); }

    public void destroy() { client.endConnection(); }

    /* ---------- JavaScript bridge: window.FolioBilling ---------- */

    public final Bridge bridge = new Bridge();

    public class Bridge {
        @JavascriptInterface public boolean isPro() { return isOwned(); }
        @JavascriptInterface public String price() { return ProBilling.this.price(); }
        @JavascriptInterface public void buy() { activity.runOnUiThread(() -> connect(ProBilling.this::launch)); }
        @JavascriptInterface public void restore() { activity.runOnUiThread(() -> connect(() -> checkPurchases(true))); }
        @JavascriptInterface public void refresh() { activity.runOnUiThread(() -> connect(null)); }
    }
}
