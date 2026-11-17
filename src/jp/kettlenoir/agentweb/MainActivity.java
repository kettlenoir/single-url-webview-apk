package jp.kettlenoir.agentweb;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-URL WebView browser with gesture-driven tabs.
 *
 * Settings live in res/values/strings.xml (app_name, start_url) and
 * res/values/strings.xml's max_tabs integer. Tab operations use two
 * fingers only, so no tab bar is shown:
 *   two-finger tap         -> open a new tab (start_url)
 *   two-finger swipe left  -> next tab
 *   two-finger swipe right -> previous tab
 *   two-finger swipe down  -> close the current tab
 * A one-finger long press on a link opens that link in a new tab
 * (at the tab limit it only shows the limit toast and opens nothing).
 * Open tabs (their URLs and the active tab) are persisted and restored
 * after the app is closed or killed.
 */
public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 1001;

    private String primaryUrl;
    private String fallbackUrl;
    private int maxTabs;

    private FrameLayout container;
    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private ValueCallback<Uri[]> filePathCallback;

    private static class Tab {
        WebView webView;
        String currentUrl;
        boolean triedFallback = false;
        boolean showingError = false;
    }

    // ---- two-finger gesture tracking ----
    private boolean twoFingerActive = false;
    private boolean gestureFired = false;
    private boolean injecting = false;
    private float gestureStartX;
    private float gestureStartY;
    private long gestureStartTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        primaryUrl = getString(R.string.start_url);
        fallbackUrl = primaryUrl;
        maxTabs = getResources().getInteger(R.integer.max_tabs);

        container = new FrameLayout(this);
        setContentView(container);

        if (savedInstanceState != null && savedInstanceState.getInt("tab_count", 0) > 0) {
            int count = Math.min(savedInstanceState.getInt("tab_count", 0), maxTabs);
            for (int i = 0; i < count; i++) {
                Tab tab = createTab();
                Bundle state = savedInstanceState.getBundle("tab_state_" + i);
                if (state != null) {
                    tab.webView.restoreState(state);
                } else {
                    tab.webView.loadUrl(primaryUrl);
                }
            }
            switchTo(Math.min(savedInstanceState.getInt("active_index", 0), tabs.size() - 1), false);
        } else if (!restorePersistedTabs()) {
            Tab tab = createTab();
            tab.currentUrl = primaryUrl;
            tab.webView.loadUrl(primaryUrl);
            switchTo(0, false);
        }
    }

    // ---- tab persistence (survives the app being closed or killed) ----
    private void persistTabs() {
        if (tabs.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tabs.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String url = tabs.get(i).currentUrl;
            sb.append(url != null ? url : primaryUrl);
        }
        getSharedPreferences("tabs", MODE_PRIVATE).edit()
                .putString("urls", sb.toString())
                .putInt("active", activeIndex)
                .apply();
    }

    private boolean restorePersistedTabs() {
        SharedPreferences prefs = getSharedPreferences("tabs", MODE_PRIVATE);
        String saved = prefs.getString("urls", null);
        if (saved == null || saved.isEmpty()) {
            return false;
        }
        String[] urls = saved.split("\n");
        int count = Math.min(urls.length, maxTabs);
        if (count <= 0) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            Tab tab = createTab();
            tab.currentUrl = urls[i];
            tab.webView.loadUrl(urls[i]);
        }
        int active = Math.min(Math.max(prefs.getInt("active", 0), 0), tabs.size() - 1);
        switchTo(active, false);
        return true;
    }

    private Tab createTab() {
        final Tab tab = new Tab();
        WebView webView = new WebView(this);
        tab.webView = webView;

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    return false; // load inside the WebView
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) {
                    return;
                }
                String url = view.getUrl();
                if (!tab.triedFallback && url != null && url.startsWith(primaryUrl)) {
                    tab.triedFallback = true;
                    view.loadUrl(fallbackUrl);
                } else if (!tab.showingError) {
                    tab.showingError = true;
                    showErrorPage(tab);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && (url.startsWith(primaryUrl) || url.startsWith(fallbackUrl))) {
                    tab.showingError = false;
                }
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    tab.currentUrl = url;
                    persistTabs();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        });

        webView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                WebView.HitTestResult result = ((WebView) view).getHitTestResult();
                if (result != null
                        && (result.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE
                            || result.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                    String url = result.getExtra();
                    if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                        openLinkInNewTab(url);
                        return true;
                    }
                }
                return false; // not a link: keep default behavior (e.g. text selection)
            }
        });

        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                return handleTouch((WebView) view, event);
            }
        });

        webView.setVisibility(View.GONE);
        container.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        tabs.add(tab);
        return tab;
    }

    // ---- two-finger gestures ----
    private boolean handleTouch(WebView view, MotionEvent event) {
        if (injecting) {
            return false;
        }
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 2) {
            twoFingerActive = true;
            gestureFired = false;
            gestureStartX = (event.getX(0) + event.getX(1)) / 2f;
            gestureStartY = (event.getY(0) + event.getY(1)) / 2f;
            gestureStartTime = SystemClock.uptimeMillis();
            // Cancel the one-finger gesture the page may have started,
            // so the page does not scroll or click under the two fingers.
            injecting = true;
            long now = SystemClock.uptimeMillis();
            view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0));
            injecting = false;
            return true;
        }

        if (twoFingerActive && event.getPointerCount() == 2) {
            if (action == MotionEvent.ACTION_MOVE && !gestureFired) {
                float cx = (event.getX(0) + event.getX(1)) / 2f;
                float cy = (event.getY(0) + event.getY(1)) / 2f;
                float dx = cx - gestureStartX;
                float dy = cy - gestureStartY;
                float swipe = 80f * getResources().getDisplayMetrics().density;
                if (Math.abs(dx) > swipe && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    gestureFired = true;
                    if (dx < 0) {
                        nextTab();
                    } else {
                        prevTab();
                    }
                } else if (dy > swipe && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                    gestureFired = true;
                    closeCurrentTab();
                }
            }
            return true; // two-finger input never reaches the page
        }

        if (action == MotionEvent.ACTION_POINTER_UP && twoFingerActive) {
            // One of the two fingers is lifting: judge a two-finger tap.
            if (!gestureFired
                    && SystemClock.uptimeMillis() - gestureStartTime < 350) {
                float cx = (event.getX(0) + event.getX(1)) / 2f;
                float cy = (event.getY(0) + event.getY(1)) / 2f;
                float slop = 24f * getResources().getDisplayMetrics().density;
                if (Math.hypot(cx - gestureStartX, cy - gestureStartY) < slop) {
                    openNewTab();
                }
            }
            twoFingerActive = false;
            return false;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            twoFingerActive = false;
        }
        return false;
    }

    // ---- tab operations ----
    private void openNewTab() {
        if (tabs.size() >= maxTabs) {
            Toast.makeText(this, "タブが上限です（" + tabs.size() + " / " + maxTabs + "）",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Tab tab = createTab();
        tab.currentUrl = primaryUrl;
        tab.webView.loadUrl(primaryUrl);
        switchTo(tabs.size() - 1, true);
    }

    private void openLinkInNewTab(String url) {
        if (tabs.size() >= maxTabs) {
            Toast.makeText(this, "タブが上限です（" + tabs.size() + " / " + maxTabs + "）",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Tab tab = createTab();
        tab.currentUrl = url;
        tab.webView.loadUrl(url);
        switchTo(tabs.size() - 1, true);
    }

    private void nextTab() {
        if (tabs.size() > 1) {
            switchTo((activeIndex + 1) % tabs.size(), true);
        }
    }

    private void prevTab() {
        if (tabs.size() > 1) {
            switchTo((activeIndex - 1 + tabs.size()) % tabs.size(), true);
        }
    }

    private void closeCurrentTab() {
        if (activeIndex < 0 || tabs.isEmpty()) {
            return;
        }
        Tab tab = tabs.remove(activeIndex);
        container.removeView(tab.webView);
        tab.webView.destroy();
        if (tabs.isEmpty()) {
            Tab fresh = createTab();
            fresh.currentUrl = primaryUrl;
            fresh.webView.loadUrl(primaryUrl);
            switchTo(0, true);
        } else {
            switchTo(Math.min(activeIndex, tabs.size() - 1), true);
        }
    }

    private void switchTo(int index, boolean announce) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).webView.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        activeIndex = index;
        persistTabs();
        if (announce) {
            Toast.makeText(this, "タブ " + (index + 1) + " / " + tabs.size(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private Tab activeTab() {
        return (activeIndex >= 0 && activeIndex < tabs.size()) ? tabs.get(activeIndex) : null;
    }

    private void showErrorPage(Tab tab) {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{background:#111;color:#eee;font-family:sans-serif;text-align:center;padding-top:20vh}"
                + "a{color:#6cf;font-size:20px}</style></head><body>"
                + "<p>AGENT WEB に接続できませんでした。</p>"
                + "<p>自宅 Wi-Fi に接続しているか確認してください。</p>"
                + "<p><a href='" + primaryUrl + "'>もう一度開く</a></p>"
                + "</body></html>";
        tab.webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Tab tab = activeTab();
            if (tab != null && tab.webView.canGoBack()) {
                tab.webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab_count", tabs.size());
        outState.putInt("active_index", activeIndex);
        for (int i = 0; i < tabs.size(); i++) {
            Bundle state = new Bundle();
            tabs.get(i).webView.saveState(state);
            outState.putBundle("tab_state_" + i, state);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistTabs();
        for (Tab tab : tabs) {
            tab.webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Tab tab : tabs) {
            tab.webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        for (Tab tab : tabs) {
            tab.webView.destroy();
        }
        tabs.clear();
        super.onDestroy();
    }
}
