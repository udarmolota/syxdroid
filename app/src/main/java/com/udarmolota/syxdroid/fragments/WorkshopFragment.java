package com.udarmolota.syxdroid.fragments;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.udarmolota.syxdroid.GameActivity;
import com.udarmolota.syxdroid.R;
import com.udarmolota.syxdroid.SteamDownloadState;
import com.udarmolota.syxdroid.game.GameInstance;
import com.udarmolota.syxdroid.game.GameInstanceManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mods from the web, two sources picked at the top (the choice is remembered):
 *
 * <ul>
 *   <li><b>mod.io</b> (the default): the mod.io site of Songs of Syx in a WebView. Its own Download
 *       button gives the mod's ZIP to any visitor, no account; the WebView hands that download to
 *       us ({@link #onDownload}), and ModDownloads saves it in the background into Download/SyxDroid.
 *       Nothing is installed here: the player installs the ZIP in Mods. That button is small and
 *       only on the mod's page, so the bar gets a big Download button there, and every card in a
 *       list gets a queue button
 *       (assets/workshop/modio-buttons.js); a queued mod's file link is read from its own page,
 *       loaded out of sight, since lists do not show it. The link is mod.io's public file URL
 *       (g-1199.modapi.io/.../files/N/download), which needs no key.</li>
 *   <li><b>Steam Workshop</b> (as in RimDroid): Steam's Workshop pages. Downloading from there needs
 *       the player's Steam sign-in (Songs of Syx's Workshop is not open to anonymous accounts), and
 *       Steam does not answer JavaSteam's look-ups of Songs of Syx items (2026-11-16), so for now it
 *       is for browsing only: {@link #STEAM_DOWNLOAD} keeps the queue and its buttons off. With it on,
 *       every card gets a download button (assets/workshop/queue-buttons.js) and the queue goes to the
 *       Game page, which signs in and downloads.</li>
 * </ul>
 */
public class WorkshopFragment extends Fragment implements com.udarmolota.syxdroid.ModDownloads.Listener {
    private static final String STEAM_URL = "https://steamcommunity.com/app/1162750/workshop/";
    private static final String MODIO_URL = "https://mod.io/g/songsofsyx";
    /** Steam Workshop downloads: off until Steam answers our item look-ups (see the class note). */
    private static final boolean STEAM_DOWNLOAD = false;
    /**
     * The mod.io / Steam Workshop switch at the top. Hidden while Steam downloads are off: a Steam tab
     * that looks like a store but cannot download anything only confused players (2026-11-17).
     */
    private static final boolean STEAM_TAB = STEAM_DOWNLOAD;
    /**
     * mod.io queue (the down-arrow buttons on mod cards and Download (N)): off. Lists carry no file
     * link, so each queued mod's page has to be opened to read it, and that look-up kept failing,
     * hidden or on screen (2026-11-16). For now a mod is downloaded from its own page only.
     */
    private static final boolean MODIO_QUEUE_ON = false;
    private static final String KEY_SOURCE = "source";
    private static final String SOURCE_MODIO = "modio";
    private static final String SOURCE_STEAM = "steam";
    private static final String STATE_URL = "workshop_url";
    /** Item pages: .../sharedfiles/filedetails/?id=N and .../workshop/filedetails/?id=N. */
    private static final Pattern ITEM_URL = Pattern.compile("/filedetails/\\?(?:[^#]*&)?id=(\\d+)");

    /** id -> title, in the order added. Kept across visits to this screen in one app session. */
    private static final Map<Long, String> QUEUE = new LinkedHashMap<>();
    /** Above this many mods the download confirmation warns about time and storage. */
    private static final int MANY_MODS = 30;

    private static final String PREFS = "workshop";
    private static final String KEY_TEXT_ZOOM = "text_zoom";
    private static final int DEFAULT_TEXT_ZOOM = 80;

    private String buttonsScript;
    private WebView web;
    private ProgressBar progress;
    private TextView tvItem;
    private Button btnQueue;
    private Button btnDownload;
    private View fabDownload;
    private long currentId = -1;
    private View offline;
    private boolean pageFailed;
    private String currentTitle;
    /** mod.io: mod page address name ("easy-mod") -> title, in the order queued; kept for the app session. */
    private static final Map<String, String> MODIO_QUEUE = new LinkedHashMap<>();
    /** A mod.io mod page: /g/songsofsyx/m/<name>. */
    private static final Pattern MODIO_MOD = Pattern.compile("/g/songsofsyx/m/([^/?#]+)");
    /** The mod's file link on its page: mod.io's public download URL. */
    private static final String FIND_FILE_LINK_JS = "(function(){var l=document.querySelectorAll('a[href*=\"/files/\"]');"
            + "for(var i=0;i<l.length;i++){var h=l[i].href||'';if(/modapi\\.io\\/.*\\/files\\/\\d+\\/download$/.test(h))return h;}return null;})()";
    private String modioScript;
    /** The mod.io mod page shown, or null. */
    private String currentSlug;
    /** The status line over the bar, and what it shows. */
    private TextView tvStatus;
    private String lookUpStatus, downloadStatus;
    /** A Download (N) look-up is running. */
    private boolean lookingUp;
    /** Loads queued mods' pages out of sight to read their file links. */
    private WebView probe;

    /** The source shown: mod.io or the Steam Workshop. */
    private boolean modio = true;
    private android.content.SharedPreferences prefs;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_workshop, container, false);
    }

    @SuppressLint("SetJavaScriptEnabled") // Steam's Workshop is a script-driven page
    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        web = v.findViewById(R.id.wv_workshop);
        progress = v.findViewById(R.id.pb_workshop);
        tvItem = v.findViewById(R.id.tv_workshop_item);
        btnQueue = v.findViewById(R.id.btn_workshop_queue);
        btnDownload = v.findViewById(R.id.btn_workshop_download);
        fabDownload = v.findViewById(R.id.fab_workshop_download);
        fabDownload.setOnClickListener(x -> downloadCurrentModio());
        offline = v.findViewById(R.id.ll_workshop_offline);
        tvStatus = v.findViewById(R.id.tv_workshop_status);
        v.findViewById(R.id.btn_workshop_retry).setOnClickListener(x -> {
            offline.setVisibility(View.GONE);
            String url = web.getUrl();
            if (url == null || url.startsWith("data:") || url.startsWith("about:")) web.loadUrl(startUrl());
            else web.reload();
        });

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
            @Override public void onReceivedTitle(WebView view, String title) {
                if (modio && currentSlug != null) {
                    currentTitle = modioTitle(title);
                    updateBar();
                } else if (currentId > 0) {
                    currentTitle = cleanTitle(title);
                    updateBar();
                }
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost() == null ? "" : uri.getHost();
                if (onSourceSite(host)) return false;
                // Links out of the site (Discord, Ko-fi, GitHub in mod descriptions) go to the browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    // no browser: stay put
                }
                return true;
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                onUrl(url, view.getTitle());
                injectButtons();   // Steam changes pages without reloading them
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageFailed = false;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  android.webkit.WebResourceError error) {
                // Only the page itself: a failed picture or script inside a page is not "offline".
                if (request.isForMainFrame()) {
                    pageFailed = true;
                    offline.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                offline.setVisibility(pageFailed ? View.VISIBLE : View.GONE);
                injectButtons();
            }
        });

        // Text size: the sites' layout is made for a desktop, so it starts smaller and is remembered.
        prefs = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        ws.setTextZoom(prefs.getInt(KEY_TEXT_ZOOM, DEFAULT_TEXT_ZOOM));
        v.findViewById(R.id.btn_workshop_text_smaller).setOnClickListener(x -> zoomText(prefs, -10));
        v.findViewById(R.id.btn_workshop_text_larger).setOnClickListener(x -> zoomText(prefs, +10));

        // The ↓ buttons on every card call back into this bridge (assets/workshop/queue-buttons.js).
        web.addJavascriptInterface(new Bridge(), "SyxDroidWorkshop");
        web.addJavascriptInterface(new ModioBridge(), "SyxDroidModio");

        btnQueue.setOnClickListener(x -> { if (modio) downloadCurrentModio(); else toggleCurrent(); });
        btnDownload.setOnClickListener(x -> { if (modio) confirmModioQueue(); else confirmDownload(); });
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() {
                        if (web != null && web.canGoBack()) {
                            web.goBack();
                        } else {
                            setEnabled(false);
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                });

        // The source: mod.io unless the player last picked the Steam Workshop.
        modio = !STEAM_TAB || !SOURCE_STEAM.equals(prefs.getString(KEY_SOURCE, SOURCE_MODIO));
        com.google.android.material.button.MaterialButtonToggleGroup sources = v.findViewById(R.id.tg_workshop_source);
        sources.setVisibility(STEAM_TAB ? View.VISIBLE : View.GONE);
        sources.check(modio ? R.id.btn_source_modio : R.id.btn_source_steam);
        sources.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            boolean m = id == R.id.btn_source_modio;
            if (m == modio) return;
            modio = m;
            prefs.edit().putString(KEY_SOURCE, m ? SOURCE_MODIO : SOURCE_STEAM).apply();
            currentId = -1;
            currentTitle = null;
            currentSlug = null;
            web.clearHistory();
            web.loadUrl(startUrl());
            updateBar();
        });
        // mod.io's Download button: the file comes to us instead of the system's download manager.
        web.setDownloadListener(this::onDownload);

        String saved = savedInstanceState != null ? savedInstanceState.getString(STATE_URL) : null;
        boolean savedFits = saved != null && saved.startsWith("https://")
                && onSourceSite(Uri.parse(saved).getHost() == null ? "" : Uri.parse(saved).getHost());
        web.loadUrl(savedFits ? saved : startUrl());
        updateBar();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // Only the page address: WebView.saveState() keeps the whole history and grew past the
        // binder limit on Steam pages in RimDroid (TransactionTooLargeException).
        if (web != null && web.getUrl() != null) outState.putString(STATE_URL, web.getUrl());
    }

    @Override
    public void onDestroyView() {
        if (probe != null) {
            probe.stopLoading();
            probe.destroy();
            probe = null;
        }
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        injectButtons();   // the queue may have been emptied by a download meanwhile
    }

    private void zoomText(android.content.SharedPreferences prefs, int delta) {
        if (web == null) return;
        int zoom = Math.max(50, Math.min(150, web.getSettings().getTextZoom() + delta));
        web.getSettings().setTextZoom(zoom);
        prefs.edit().putInt(KEY_TEXT_ZOOM, zoom).apply();
    }

    private String startUrl() {
        return modio ? MODIO_URL : STEAM_URL;
    }

    /** Whether a link stays in the WebView: the shown source's own sites. */
    private boolean onSourceSite(String host) {
        if (modio) return host.equals("mod.io") || host.endsWith(".mod.io") || host.endsWith("modapi.io");
        return host.endsWith("steamcommunity.com") || host.endsWith("steampowered.com")
                || host.endsWith("steamstatic.com");
    }

    // ---- mod.io: downloads go to Download/SyxDroid, in the background ----

    /** mod.io's own Download button: the file goes to the background downloads instead. */
    private void onDownload(String url, String userAgent, String contentDisposition, String mimeType, long length) {
        if (!isAdded()) return;
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            Toast.makeText(requireContext(), R.string.modio_cannot_take, Toast.LENGTH_LONG).show();
            return;
        }
        String name = currentTitle != null ? currentTitle
                : android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
        queueDownload(url, userAgent, name);
    }

    /** Hands one file to the background downloads (ModDownloads) and says so. */
    private void queueDownload(String url, String userAgent, String name) {
        String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
        com.udarmolota.syxdroid.ModDownloads.get().add(requireContext(), url, name, userAgent, cookies);
        Toast.makeText(requireContext(), getString(R.string.moddl_queued, name), Toast.LENGTH_SHORT).show();
    }

    /** The bar's Download on a mod page: the same file its own Download button gives. */
    private void downloadCurrentModio() {
        if (web == null || currentSlug == null) return;
        String name = currentTitle != null ? currentTitle : currentSlug;
        web.evaluateJavascript(FIND_FILE_LINK_JS, result -> {
            String url = jsString(result);
            if (url == null) {
                Toast.makeText(requireContext(), R.string.modio_no_file_link, Toast.LENGTH_LONG).show();
                return;
            }
            queueDownload(url, web.getSettings().getUserAgentString(), name);
        });
    }

    /** The value of a string result of evaluateJavascript, or null. */
    @Nullable
    private static String jsString(@Nullable String result) {
        if (result == null) return null;
        try {
            Object v = new org.json.JSONTokener(result).nextValue();
            return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /**
     * Download (N) for the mods queued in lists: lists do not carry a mod's file link, so each mod's
     * page is opened in turn, here on screen, the link read and handed to the background downloads,
     * and then the list comes back. On screen, not in a hidden WebView: an unseen page is rendered
     * slowly and missed the time limit (2026-11-16: three of four "not found"). Leaving this page
     * stops the look-up, never a download.
     */
    private void confirmModioQueue() {
        List<Map.Entry<String, String>> items;
        synchronized (MODIO_QUEUE) { items = new ArrayList<>(MODIO_QUEUE.entrySet()); }
        if (items.isEmpty() || lookingUp || web == null) return;
        lookingUp = true;
        String back = web.getUrl();
        lookUp(items, 0, new ArrayList<>(), back != null ? back : MODIO_URL);
    }

    private void lookUp(List<Map.Entry<String, String>> items, int index, List<String> notFound, String back) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        if (web == null || !isAdded()) { lookingUp = false; return; }
        if (index >= items.size()) {
            lookingUp = false;
            showLookUp(null);
            web.loadUrl(back);            // back to the list the player queued from
            if (!notFound.isEmpty()) {
                StringBuilder sb = new StringBuilder(getString(R.string.modio_no_file_link));
                for (String n : notFound) sb.append("\n• ").append(n);
                new MaterialAlertDialogBuilder(requireContext()).setMessage(sb.toString())
                        .setPositiveButton(android.R.string.ok, null).show();
            }
            updateBar();
            return;
        }
        String slug = items.get(index).getKey();
        String title = items.get(index).getValue();
        showLookUp(getString(R.string.modio_looking_up, index + 1, items.size(), title));
        web.loadUrl("https://mod.io/g/songsofsyx/m/" + slug);
        // The page builds itself with scripts: ask for the link until it is there (at most ~20 s).
        final int[] tries = {0};
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (web == null || !isAdded()) { lookingUp = false; return; }
                web.evaluateJavascript(FIND_FILE_LINK_JS, result -> {
                    String url = jsString(result);
                    if (url == null && ++tries[0] < 40) {
                        main.postDelayed(this, 500);
                        return;
                    }
                    if (url == null) {
                        notFound.add(title);
                    } else {
                        String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                        com.udarmolota.syxdroid.ModDownloads.get().add(requireContext(), url, title,
                                web.getSettings().getUserAgentString(), cookies);
                        synchronized (MODIO_QUEUE) { MODIO_QUEUE.remove(slug); }
                    }
                    lookUp(items, index + 1, notFound, back);
                });
            }
        };
        main.postDelayed(poll, 800);
    }

    /** The status line over the bar: a look-up, or the background downloads, or nothing. */
    private void showLookUp(@Nullable String text) {
        lookUpStatus = text;
        showStatus();
    }

    private void showStatus() {
        if (tvStatus == null) return;
        String t = lookUpStatus != null ? lookUpStatus : downloadStatus;
        tvStatus.setText(t);
        tvStatus.setVisibility(t != null ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onModDownloads(@Nullable String status) {
        downloadStatus = status;
        showStatus();
    }

    @Override
    public void onStart() {
        super.onStart();
        com.udarmolota.syxdroid.ModDownloads.get().setListener(this);
    }

    @Override
    public void onStop() {
        com.udarmolota.syxdroid.ModDownloads.get().clearListener(this);
        super.onStop();
    }

    /** What the mod.io page script may call: add or remove a mod in our queue, nothing else. */
    private final class ModioBridge {
        @android.webkit.JavascriptInterface
        public boolean isQueued(String slug) {
            synchronized (MODIO_QUEUE) { return slug != null && MODIO_QUEUE.containsKey(slug); }
        }

        @android.webkit.JavascriptInterface
        public boolean toggle(String slug, String title) {
            if (slug == null || !slug.matches("[A-Za-z0-9_.-]+")) return false;
            boolean queued;
            synchronized (MODIO_QUEUE) {
                if (MODIO_QUEUE.containsKey(slug)) {
                    MODIO_QUEUE.remove(slug);
                    queued = false;
                } else {
                    String t = title == null ? "" : title.trim();
                    if (t.length() > 120) t = t.substring(0, 120);
                    MODIO_QUEUE.put(slug, t.isEmpty() ? slug : t);
                    queued = true;
                }
            }
            View root = getView();
            if (root != null) root.post(WorkshopFragment.this::updateBar);
            return queued;
        }
    }

    /** Adds (or refreshes) the ↓ buttons on the cards of the page being shown. */
    private void injectButtons() {
        if (web == null) return;
        if (modio) {
            injectModioButtons();
            return;
        }
        if (!STEAM_DOWNLOAD) return;
        String url = web.getUrl();
        String host = url == null ? null : Uri.parse(url).getHost();
        if (host == null || !host.endsWith("steamcommunity.com")) return;
        if (buttonsScript == null) {
            try (java.io.InputStream in = requireContext().getAssets().open("workshop/queue-buttons.js")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                buttonsScript = out.toString("UTF-8");
            } catch (java.io.IOException e) {
                android.util.Log.w("WorkshopFragment", "No queue-buttons.js", e);
                return;
            }
        }
        web.evaluateJavascript(buttonsScript, null);
    }

    private void injectModioButtons() {
        if (!MODIO_QUEUE_ON) return;
        String url = web.getUrl();
        String host = url == null ? null : Uri.parse(url).getHost();
        if (host == null || !(host.equals("mod.io") || host.endsWith(".mod.io"))) return;
        if (modioScript == null) {
            try (java.io.InputStream in = requireContext().getAssets().open("workshop/modio-buttons.js")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                modioScript = out.toString("UTF-8");
            } catch (java.io.IOException e) {
                android.util.Log.w("WorkshopFragment", "No modio-buttons.js", e);
                return;
            }
        }
        web.evaluateJavascript(modioScript, null);
    }

    /**
     * What the page script may call. Runs on a WebView background thread; the queue is touched only
     * under its own lock and the bar is updated on the main thread. All the page can do through this
     * is add or remove a Workshop id in our queue.
     */
    private final class Bridge {
        @android.webkit.JavascriptInterface
        public boolean isQueued(String id) {
            long n = parseId(id);
            synchronized (QUEUE) { return n > 0 && QUEUE.containsKey(n); }
        }

        @android.webkit.JavascriptInterface
        public boolean toggle(String id, String title) {
            long n = parseId(id);
            if (n <= 0) return false;
            boolean queued;
            synchronized (QUEUE) {
                if (QUEUE.containsKey(n)) {
                    QUEUE.remove(n);
                    queued = false;
                } else {
                    String t = title == null ? "" : title.trim();
                    if (t.length() > 120) t = t.substring(0, 120);
                    QUEUE.put(n, t.isEmpty() ? String.valueOf(n) : t);
                    queued = true;
                }
            }
            View root = getView();
            if (root != null) root.post(WorkshopFragment.this::updateBar);
            return queued;
        }

        private long parseId(String id) {
            try {
                return id == null ? -1 : Long.parseLong(id.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    private void onUrl(String url, String title) {
        if (modio) {
            Matcher mm = url == null ? null : MODIO_MOD.matcher(url);
            currentSlug = mm != null && mm.find() ? mm.group(1) : null;
            currentTitle = currentSlug != null ? modioTitle(title) : null;
            updateBar();
            return;
        }
        Matcher m = url == null ? null : ITEM_URL.matcher(url);
        if (m != null && m.find()) {
            try {
                currentId = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                currentId = -1;
            }
            currentTitle = cleanTitle(title);
        } else {
            currentId = -1;
            currentTitle = null;
        }
        updateBar();
    }

    /** "Easy Mod (V70) for Songs of Syx - mod.io" -> "Easy Mod (V70)". */
    @Nullable
    private static String modioTitle(@Nullable String title) {
        if (title == null) return null;
        String t = title.replaceAll("\\s*-\\s*mod\\.io\\s*$", "").replaceAll("\\s+for Songs of Syx\\s*$", "").trim();
        return t.isEmpty() || t.startsWith("http") ? null : t;
    }

    /** "Steam Workshop::Better Maps" -> "Better Maps". */
    @Nullable
    private static String cleanTitle(@Nullable String title) {
        if (title == null) return null;
        int sep = title.indexOf("::");
        String t = (sep >= 0 ? title.substring(sep + 2) : title).trim();
        return t.isEmpty() || t.startsWith("http") ? null : t;
    }

    private void toggleCurrent() {
        if (currentId <= 0) return;
        synchronized (QUEUE) {
            if (QUEUE.containsKey(currentId)) QUEUE.remove(currentId);
            else QUEUE.put(currentId, currentTitle != null ? currentTitle : String.valueOf(currentId));
        }
        updateBar();
        injectButtons();   // repaint the same item's button in lists further back
    }

    private void updateBar() {
        if (tvItem == null) return;
        fabDownload.setVisibility(View.GONE);
        if (modio) {
            int size;
            synchronized (MODIO_QUEUE) { size = MODIO_QUEUE.size(); }
            if (currentSlug != null) {
                tvItem.setText(currentTitle != null ? currentTitle : currentSlug);
                btnQueue.setVisibility(View.GONE);      // the corner button downloads it
                fabDownload.setVisibility(View.VISIBLE);
            } else {
                tvItem.setText(R.string.workshop_modio_hint);
                btnQueue.setVisibility(View.GONE);
            }
            btnDownload.setVisibility(MODIO_QUEUE_ON && size > 0 ? View.VISIBLE : View.GONE);
            btnDownload.setEnabled(size > 0);
            btnDownload.setText(getString(R.string.workshop_download_n, size));
            return;
        }
        if (!STEAM_DOWNLOAD) {
            // Steam: browsing only for now
            tvItem.setText(R.string.workshop_steam_later);
            btnQueue.setVisibility(View.GONE);
            btnDownload.setVisibility(View.GONE);
            return;
        }
        btnDownload.setVisibility(View.VISIBLE);
        boolean onItem = currentId > 0;
        boolean queued;
        int size;
        synchronized (QUEUE) {
            queued = QUEUE.containsKey(currentId);
            size = QUEUE.size();
        }
        if (onItem) {
            tvItem.setText(currentTitle != null ? currentTitle : getString(R.string.workshop_item_id, currentId));
            btnQueue.setVisibility(View.VISIBLE);
            btnQueue.setText(queued ? R.string.workshop_remove_from_queue : R.string.workshop_add_to_queue);
        } else {
            tvItem.setText(R.string.workshop_open_a_mod);
            btnQueue.setVisibility(View.GONE);
        }
        btnDownload.setEnabled(size > 0);
        btnDownload.setText(getString(R.string.workshop_download_n, size));
    }

    private void confirmDownload() {
        List<String> titles;
        synchronized (QUEUE) { titles = new ArrayList<>(QUEUE.values()); }
        if (titles.isEmpty()) return;
        if (SteamDownloadState.get().isDownloading()) {
            Toast.makeText(requireContext(), R.string.workshop_download_busy, Toast.LENGTH_LONG).show();
            return;
        }
        List<GameInstance> instances = new ArrayList<>(GameInstanceManager.requireSingleton().getInstances());
        if (instances.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_instances, Toast.LENGTH_LONG).show();
            return;
        }
        if (instances.size() == 1) {
            confirmFor(instances.get(0), titles);
            return;
        }
        String[] names = new String[instances.size()];
        for (int i = 0; i < names.length; i++) names[i] = instances.get(i).getName();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.workshop_pick_instance)
                .setItems(names, (d, which) -> confirmFor(instances.get(which), titles))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmFor(GameInstance gi, List<String> titles) {
        StringBuilder list = new StringBuilder();
        for (String t : titles) list.append("• ").append(t).append('\n');
        String message = getString(R.string.workshop_download_message, gi.getName(), list.toString().trim());
        if (titles.size() > MANY_MODS) {
            message = getString(R.string.workshop_download_many, titles.size()) + "\n\n" + message;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.workshop_download_title, titles.size()))
                .setMessage(message)
                .setPositiveButton(R.string.workshop_download_go, (d, w) -> startDownload(gi))
                .setNeutralButton(R.string.workshop_clear_queue, (d, w) -> {
                    synchronized (QUEUE) { QUEUE.clear(); }
                    updateBar();
                    injectButtons();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startDownload(GameInstance gi) {
        long[] ids;
        String[] titles;
        synchronized (QUEUE) {
            ids = new long[QUEUE.size()];
            titles = new String[QUEUE.size()];
            int i = 0;
            for (Map.Entry<Long, String> e : QUEUE.entrySet()) {
                ids[i] = e.getKey();
                titles[i] = e.getValue();
                i++;
            }
            QUEUE.clear();
        }
        Bundle args = new Bundle();
        args.putLongArray(DownloadFragment.ARG_MOD_IDS, ids);
        args.putStringArray(DownloadFragment.ARG_MOD_TITLES, titles);
        args.putString(DownloadFragment.ARG_MOD_INSTANCE, gi.getName());
        View root = getView();
        if (root != null) Navigation.findNavController(root).navigate(R.id.action_download_mods, args);
    }
}
