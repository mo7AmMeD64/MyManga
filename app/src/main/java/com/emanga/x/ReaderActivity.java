package com.emanga.x;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReaderActivity extends Activity {

    // ========== ثوابت Intent ==========
    public static final String EXTRA_CHAPTER_URL   = "chapter_url";
    public static final String EXTRA_CHAPTER_NAME  = "chapter_name";
    public static final String EXTRA_MANGA_TITLE   = "manga_title";
    public static final String EXTRA_CHAPTERS_JSON = "chapters_json";  // JSONArray كاملة
    public static final String EXTRA_MANGA_IMG     = "manga_img";

    // ========== حالة القارئ ==========
    private String chapterUrl;
    private String chapterName;
    private String mangaTitle;
    private String mangaImg;
    private List<ChapterItem> chapterList = new ArrayList<>();
    private int currentChapterIndex = -1;

    // ========== إعدادات العرض ==========
    private float brightnessLevel = 1.0f;
    private float currentZoom    = 1.0f;
    private boolean isUIVisible  = true;
    private boolean isWebtoonMode = true; // وضع Webtoon (تمرير عمودي) افتراضي

    // ========== Views ==========
    private ScrollView scrollView;
    private LinearLayout pagesContainer;
    private View uiTopBar, uiBottomBar;
    private TextView tvChapterTitle, tvMangaTitle, tvPageInfo, tvZoom;
    private View brightnessOverlay;
    private ProgressBar loadingBar;
    private View autoNextScreen;
    private TextView tvAutoNextName, tvAutoNextCount;
    private View btnPrevChapter, btnNextChapter;

    // ========== نظام التحميل ==========
    private ExecutorService executor = Executors.newFixedThreadPool(4);
    private Handler mainHandler   = new Handler(Looper.getMainLooper());
    private List<String> imageUrls = new ArrayList<>();
    private int loadedCount        = 0;

    // ========== التمرير التلقائي ==========
    private boolean isAutoScrolling = false;
    private Handler autoScrollHandler = new Handler(Looper.getMainLooper());
    private Runnable autoScrollRunnable;
    private float autoScrollSpeed = 2f;

    // ========== الانتقال التلقائي ==========
    private boolean autoNextEnabled = false;
    private Handler autoNextHandler = new Handler(Looper.getMainLooper());
    private Runnable autoNextRunnable;
    private int autoNextCountdown   = 5;

    // ========== إيماءات ==========
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float lastTouchX, lastTouchY;

    // ========== ألوان ==========
    private static final int COLOR_BG      = 0xFF0A0A0A;
    private static final int COLOR_SURFACE = 0xFF1A1A1A;
    private static final int COLOR_ACCENT  = 0xFFFF6B00;
    private static final int COLOR_TEXT    = 0xFFFFFFFF;
    private static final int COLOR_HINT    = 0x80FFFFFF;

    // ========== dp helper ==========
    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    // ========== نموذج الفصل ==========
    static class ChapterItem {
        String name, link;
        ChapterItem(String name, String link) {
            this.name = name;
            this.link = link;
        }
    }

    // ============================================================
    //  onCreate
    // ============================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // شاشة كاملة
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        getWindow().setStatusBarColor(Color.TRANSPARENT);

        // استقبال البيانات
        Intent intent = getIntent();
        chapterUrl  = intent.getStringExtra(EXTRA_CHAPTER_URL);
        chapterName = intent.getStringExtra(EXTRA_CHAPTER_NAME);
        mangaTitle  = intent.getStringExtra(EXTRA_MANGA_TITLE);
        mangaImg    = intent.getStringExtra(EXTRA_MANGA_IMG);

        // تحليل قائمة الفصول
        try {
            String chaptersJson = intent.getStringExtra(EXTRA_CHAPTERS_JSON);
            if (chaptersJson != null) {
                JSONArray arr = new JSONArray(chaptersJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    chapterList.add(new ChapterItem(
                        obj.optString("name", "فصل " + i),
                        obj.optString("link", "")
                    ));
                }
            }
        } catch (Exception e) { e.printStackTrace(); }

        // إيجاد الفصل الحالي
        for (int i = 0; i < chapterList.size(); i++) {
            if (chapterList.get(i).link.equals(chapterUrl)) {
                currentChapterIndex = i;
                break;
            }
        }

        // بناء الواجهة
        buildUI();
        setupGestures();

        // تحميل الفصل
        loadChapter(chapterUrl, chapterName);
    }

    // ============================================================
    //  بناء الواجهة كاملاً بـ Java (بدون XML)
    // ============================================================
    private void buildUI() {
        // الحاوية الرئيسية
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);
        setContentView(root);

        // ===== منطقة الصور (ScrollView) =====
        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_BG);
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        FrameLayout.LayoutParams svParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        root.addView(scrollView, svParams);

        pagesContainer = new LinearLayout(this);
        pagesContainer.setOrientation(LinearLayout.VERTICAL);
        pagesContainer.setBackgroundColor(COLOR_BG);
        ScrollView.LayoutParams pcParams = new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        scrollView.addView(pagesContainer, pcParams);

        // ===== طبقة السطوع =====
        brightnessOverlay = new View(this);
        brightnessOverlay.setBackgroundColor(Color.BLACK);
        brightnessOverlay.setAlpha(0f);
        brightnessOverlay.setClickable(false);
        root.addView(brightnessOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // ===== شريط التقدم العلوي =====
        View progressBar = new View(this);
        progressBar.setId(View.generateViewId());
        progressBar.setBackgroundColor(COLOR_ACCENT);
        FrameLayout.LayoutParams pbParams = new FrameLayout.LayoutParams(0, dp(3));
        pbParams.gravity = android.view.Gravity.TOP;
        root.addView(progressBar, pbParams);
        setupScrollProgress(progressBar, pbParams, root);

        // ===== شريط علوي (Tachiyomi) =====
        uiTopBar = buildTopBar();
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        topParams.gravity = android.view.Gravity.TOP;
        root.addView(uiTopBar, topParams);

        // ===== شريط سفلي =====
        uiBottomBar = buildBottomBar();
        FrameLayout.LayoutParams botParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        botParams.gravity = android.view.Gravity.BOTTOM;
        root.addView(uiBottomBar, botParams);

        // ===== مؤشر الصفحة =====
        tvPageInfo = new TextView(this);
        tvPageInfo.setTextColor(COLOR_TEXT);
        tvPageInfo.setTextSize(12);
        tvPageInfo.setPadding(dp(12), dp(5), dp(12), dp(5));
        tvPageInfo.setBackgroundColor(0xBB000000);
        tvPageInfo.setVisibility(View.GONE);
        FrameLayout.LayoutParams piParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        piParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        piParams.bottomMargin = dp(90);
        root.addView(tvPageInfo, piParams);

        // ===== شاشة الانتقال التلقائي =====
        autoNextScreen = buildAutoNextScreen();
        root.addView(autoNextScreen, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        autoNextScreen.setVisibility(View.GONE);

        // ===== مؤشر التحميل =====
        loadingBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        loadingBar.setIndeterminate(true);
        loadingBar.getIndeterminateDrawable().setColorFilter(
            COLOR_ACCENT, android.graphics.PorterDuff.Mode.SRC_IN
        );
        FrameLayout.LayoutParams lbParams = new FrameLayout.LayoutParams(
            dp(48), dp(48)
        );
        lbParams.gravity = android.view.Gravity.CENTER;
        root.addView(loadingBar, lbParams);
    }

    // ----- بناء الشريط العلوي -----
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xF0000000);
        bar.setPadding(dp(12), dp(42), dp(12), dp(12));

        // صف 1: إغلاق + عناوين + زر الانتقال
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // زر الإغلاق
        TextView btnClose = makeIconBtn("✕", v -> finish());
        row1.addView(btnClose);

        // العناوين
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(12), 0, dp(12), 0);

        tvChapterTitle = new TextView(this);
        tvChapterTitle.setTextColor(COLOR_TEXT);
        tvChapterTitle.setTextSize(13);
        tvChapterTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvChapterTitle.setMaxLines(1);
        tvChapterTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);

        tvMangaTitle = new TextView(this);
        tvMangaTitle.setTextColor(COLOR_HINT);
        tvMangaTitle.setTextSize(10);
        tvMangaTitle.setMaxLines(1);
        tvMangaTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);

        titles.addView(tvChapterTitle);
        titles.addView(tvMangaTitle);
        row1.addView(titles, titleParams);

        // زر الانتقال التلقائي
        TextView btnAutoNext = makeTagBtn("⏭ تلقائي", v -> toggleAutoNext((TextView) v));
        row1.addView(btnAutoNext);

        bar.addView(row1, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // صف 2: تمرير + أزرار
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2Params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        r2Params.topMargin = dp(10);

        TextView btnScroll = makeTagBtn("▶ تمرير", v -> toggleAutoScroll((TextView) v));
        row2.addView(btnScroll);

        // فراغ
        View spacer = new View(this);
        row2.addView(spacer, new LinearLayout.LayoutParams(0,1,1f));

        // زر وضع العين
        TextView btnEye = makeTagBtn("👁 حماية", v -> toggleEyeCare((TextView) v));
        row2.addView(btnEye);

        bar.addView(row2, r2Params);
        return bar;
    }

    // ----- بناء الشريط السفلي -----
    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xF0000000);
        bar.setPadding(dp(16), dp(12), dp(16), dp(24));
        bar.setGravity(android.view.Gravity.CENTER);

        // شريط السطوع
        LinearLayout brightnessRow = new LinearLayout(this);
        brightnessRow.setOrientation(LinearLayout.HORIZONTAL);
        brightnessRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView sunIcon = new TextView(this);
        sunIcon.setText("☀");
        sunIcon.setTextColor(COLOR_HINT);
        sunIcon.setTextSize(16);
        brightnessRow.addView(sunIcon);

        android.widget.SeekBar brightnessBar = new android.widget.SeekBar(this);
        brightnessBar.setMax(80);
        brightnessBar.setProgress(0);
        brightnessBar.getProgressDrawable().setColorFilter(COLOR_ACCENT,
            android.graphics.PorterDuff.Mode.SRC_IN);
        brightnessBar.getThumb().setColorFilter(COLOR_ACCENT,
            android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams bbParams = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bbParams.setMargins(dp(10), 0, dp(10), 0);
        brightnessRow.addView(brightnessBar, bbParams);

        brightnessBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int prog, boolean user) {
                brightnessOverlay.setAlpha(prog / 100f);
            }
            public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });

        bar.addView(brightnessRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // صف الأزرار
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams brParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        brParams.topMargin = dp(12);

        // زر السابق
        btnPrevChapter = makeNavBtn("❮ السابق", v -> navigateChapter(1));
        // زر التكبير -
        TextView btnZoomOut = makeIconBtn("−", v -> changeZoom(-0.25f));
        btnZoomOut.setTextSize(22);
        // نص الزوم
        tvZoom = new TextView(this);
        tvZoom.setText("100%");
        tvZoom.setTextColor(COLOR_TEXT);
        tvZoom.setTextSize(13);
        tvZoom.setTypeface(null, android.graphics.Typeface.BOLD);
        tvZoom.setMinWidth(dp(56));
        tvZoom.setGravity(android.view.Gravity.CENTER);
        // زر التكبير +
        TextView btnZoomIn = makeIconBtn("+", v -> changeZoom(0.25f));
        btnZoomIn.setTextSize(22);
        // زر التالي
        btnNextChapter = makeNavBtn("التالي ❯", v -> navigateChapter(-1));

        btnRow.addView(btnPrevChapter);
        LinearLayout.LayoutParams zoomMargin = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        zoomMargin.setMargins(dp(8), 0, dp(8), 0);
        btnRow.addView(btnZoomOut, zoomMargin);
        btnRow.addView(tvZoom);
        btnRow.addView(btnZoomIn, zoomMargin);
        btnRow.addView(btnNextChapter);

        bar.addView(btnRow, brParams);
        return bar;
    }

    // ----- بناء شاشة الانتقال التلقائي -----
    private View buildAutoNextScreen() {
        RelativeLayout screen = new RelativeLayout(this);
        screen.setBackgroundColor(COLOR_BG);
        screen.setGravity(android.view.Gravity.CENTER);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(android.view.Gravity.CENTER);

        // "الفصل التالي"
        TextView lblNext = new TextView(this);
        lblNext.setText("الفصل التالي");
        lblNext.setTextColor(COLOR_HINT);
        lblNext.setTextSize(13);
        lblNext.setGravity(android.view.Gravity.CENTER);
        content.addView(lblNext);

        // اسم الفصل
        tvAutoNextName = new TextView(this);
        tvAutoNextName.setTextColor(COLOR_TEXT);
        tvAutoNextName.setTextSize(17);
        tvAutoNextName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAutoNextName.setGravity(android.view.Gravity.CENTER);
        tvAutoNextName.setPadding(dp(20), dp(8), dp(20), dp(20));
        content.addView(tvAutoNextName);

        // دائرة العد التنازلي
        tvAutoNextCount = new TextView(this);
        tvAutoNextCount.setText("5");
        tvAutoNextCount.setTextColor(COLOR_ACCENT);
        tvAutoNextCount.setTextSize(52);
        tvAutoNextCount.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAutoNextCount.setGravity(android.view.Gravity.CENTER);
        tvAutoNextCount.setBackgroundResource(0);
        // دائرة مرسومة
        tvAutoNextCount.setBackground(makeCircleDrawable());
        tvAutoNextCount.setPadding(dp(24), dp(20), dp(24), dp(20));
        content.addView(tvAutoNextCount, new LinearLayout.LayoutParams(dp(120), dp(120)));

        // الأزرار
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams btnsParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        btnsParams.topMargin = dp(24);

        TextView btnGo = new TextView(this);
        btnGo.setText("انتقل الآن");
        btnGo.setTextColor(COLOR_TEXT);
        btnGo.setTextSize(14);
        btnGo.setTypeface(null, android.graphics.Typeface.BOLD);
        btnGo.setBackground(makePillDrawable(COLOR_ACCENT));
        btnGo.setPadding(dp(24), dp(12), dp(24), dp(12));
        btnGo.setOnClickListener(v -> doAutoNext());

        TextView btnCancel = new TextView(this);
        btnCancel.setText("إلغاء");
        btnCancel.setTextColor(COLOR_HINT);
        btnCancel.setTextSize(14);
        btnCancel.setBackground(makePillDrawable(0x22FFFFFF));
        btnCancel.setPadding(dp(24), dp(12), dp(24), dp(12));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cancelParams.setMargins(dp(12), 0, 0, 0);
        btnCancel.setOnClickListener(v -> cancelAutoNext());

        btns.addView(btnGo);
        btns.addView(btnCancel, cancelParams);
        content.addView(btns, btnsParams);

        RelativeLayout.LayoutParams cParams = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        screen.addView(content, cParams);
        return screen;
    }

    // ============================================================
    //  تحميل الفصل
    // ============================================================
    private void loadChapter(String url, String name) {
        imageUrls.clear();
        loadedCount = 0;
        pagesContainer.removeAllViews();
        loadingBar.setVisibility(View.VISIBLE);

        if (tvChapterTitle != null) tvChapterTitle.setText(name);
        if (tvMangaTitle   != null) tvMangaTitle.setText(mangaTitle != null ? mangaTitle : "");

        updateNavButtons();

        executor.execute(() -> {
            try {
                String html = fetchUrl(url);
                Document doc = Jsoup.parse(html);

                // جلب الصور - نفس منطق MainActivity
                Elements imgs = doc.select(
                    "div.image_list canvas[data-src], div.image_list img, .reading-content img, #readerarea img"
                );

                JSONArray pages = new JSONArray();
                for (Element img : imgs) {
                    String src = img.hasAttr("data-src") ? img.absUrl("data-src") : img.absUrl("src");
                    if (!src.isEmpty() && !src.contains("loader") && !src.contains("pixel")) {
                        pages.put(src);
                    }
                }

                // تحليل النتيجة
                final List<String> urls = new ArrayList<>();
                for (int i = 0; i < pages.length(); i++) urls.add(pages.getString(i));

                mainHandler.post(() -> {
                    loadingBar.setVisibility(View.GONE);
                    if (urls.isEmpty()) {
                        showError("لم يتم العثور على صور في هذا الفصل");
                    } else {
                        imageUrls.addAll(urls);
                        renderPages();
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    loadingBar.setVisibility(View.GONE);
                    showError("خطأ في التحميل: " + e.getMessage());
                });
            }
        });
    }

    // ============================================================
    //  رسم صفحات الفصل
    // ============================================================
    private void renderPages() {
        for (int i = 0; i < imageUrls.size(); i++) {
            final int index = i;
            final String url = imageUrls.get(i);

            // حاوية الصفحة
            FrameLayout pageFrame = new FrameLayout(this);
            pageFrame.setBackgroundColor(COLOR_BG);
            int minHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.6);
            LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            pagesContainer.addView(pageFrame, frameParams);
            pageFrame.setMinimumHeight(minHeight);

            // الصورة
            ImageView imageView = new ImageView(this);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setAdjustViewBounds(true);
            imageView.setVisibility(View.INVISIBLE);
            pageFrame.addView(imageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            // مؤشر تحميل الصفحة
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyle);
            pb.setIndeterminate(true);
            pb.getIndeterminateDrawable().setColorFilter(COLOR_ACCENT,
                android.graphics.PorterDuff.Mode.SRC_IN);
            FrameLayout.LayoutParams pbParams = new FrameLayout.LayoutParams(dp(32), dp(32));
            pbParams.gravity = android.view.Gravity.CENTER;
            pageFrame.addView(pb, pbParams);

            // رقم الصفحة
            TextView pageNum = new TextView(this);
            pageNum.setText((index + 1) + " / " + imageUrls.size());
            pageNum.setTextColor(COLOR_TEXT);
            pageNum.setTextSize(10);
            pageNum.setPadding(dp(8), dp(3), dp(8), dp(3));
            pageNum.setBackgroundColor(0xBB000000);
            FrameLayout.LayoutParams numParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            numParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
            numParams.setMargins(0, 0, dp(8), dp(8));
            pageFrame.addView(pageNum, numParams);

            // تحميل الصورة
            loadImage(url, index, imageView, pb, pageFrame, minHeight);
        }

        // أزرار التنقل في نهاية الفصل
        addBottomNavButtons();
    }

    private void loadImage(String url, int index, ImageView imgView,
                           ProgressBar pb, FrameLayout frame, int minHeight) {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);

                InputStream is = conn.getInputStream();
                final Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();

                mainHandler.post(() -> {
                    if (bmp != null) {
                        imgView.setImageBitmap(bmp);
                        imgView.setVisibility(View.VISIBLE);
                        frame.setMinimumHeight(0);
                        pb.setVisibility(View.GONE);

                        imgView.animate().alpha(1f).setDuration(300).start();
                        imgView.setAlpha(0f);
                        imgView.animate().alpha(1f).setDuration(250)
                            .setInterpolator(new AccelerateDecelerateInterpolator()).start();

                        loadedCount++;
                        // فحص نهاية الفصل
                        checkAutoNextTrigger();
                    } else {
                        pb.setVisibility(View.GONE);
                        showPageError(frame, index + 1);
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    pb.setVisibility(View.GONE);
                    showPageError(frame, index + 1);
                });
            }
        });
    }

    private void addBottomNavButtons() {
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(android.view.Gravity.CENTER);
        navRow.setPadding(dp(20), dp(24), dp(20), dp(80));

        if (currentChapterIndex < chapterList.size() - 1) {
            ChapterItem prev = chapterList.get(currentChapterIndex + 1);
            TextView btnPrev = makeNavBtn("❮ " + prev.name, v -> openChapter(currentChapterIndex + 1));
            navRow.addView(btnPrev, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        if (currentChapterIndex > 0) {
            ChapterItem next = chapterList.get(currentChapterIndex - 1);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (currentChapterIndex < chapterList.size() - 1) np.setMargins(dp(10), 0, 0, 0);
            TextView btnNext = makeNavBtn(next.name + " ❯", v -> openChapter(currentChapterIndex - 1));
            navRow.addView(btnNext, np);
        }

        pagesContainer.addView(navRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    // ============================================================
    //  التنقل بين الفصول
    // ============================================================
    private void navigateChapter(int direction) {
        int targetIdx = currentChapterIndex + direction;
        if (targetIdx >= 0 && targetIdx < chapterList.size()) {
            openChapter(targetIdx);
        } else {
            showToast(direction > 0 ? "لا يوجد فصل سابق" : "لا يوجد فصل تالٍ");
        }
    }

    private void openChapter(int index) {
        if (index < 0 || index >= chapterList.size()) return;
        currentChapterIndex = index;
        ChapterItem ch = chapterList.get(index);
        chapterUrl  = ch.link;
        chapterName = ch.name;
        scrollView.scrollTo(0, 0);
        loadChapter(chapterUrl, chapterName);
    }

    private void updateNavButtons() {
        if (btnPrevChapter != null)
            btnPrevChapter.setAlpha(currentChapterIndex < chapterList.size() - 1 ? 1f : 0.3f);
        if (btnNextChapter != null)
            btnNextChapter.setAlpha(currentChapterIndex > 0 ? 1f : 0.3f);
    }

    // ============================================================
    //  الانتقال التلقائي
    // ============================================================
    private void toggleAutoNext(TextView btn) {
        autoNextEnabled = !autoNextEnabled;
        if (autoNextEnabled) {
            btn.setTextColor(COLOR_ACCENT);
            btn.setBackground(makePillDrawable(0x22FF6B00));
            showToast("✅ سيتم الانتقال تلقائياً عند الانتهاء");
        } else {
            btn.setTextColor(COLOR_HINT);
            btn.setBackground(makePillDrawable(0x22FFFFFF));
            showToast("⏹ تم إيقاف الانتقال التلقائي");
            cancelAutoNext();
        }
    }

    private void checkAutoNextTrigger() {
        if (!autoNextEnabled) return;
        if (autoNextScreen.getVisibility() == View.VISIBLE) return;
        if (loadedCount < imageUrls.size()) return; // انتظر حتى يكتمل التحميل

        // تحقق من الوصول لنهاية الفصل عبر scroll
        scrollView.post(() -> {
            int scrollY     = scrollView.getScrollY();
            int totalHeight = pagesContainer.getHeight() - scrollView.getHeight();
            if (totalHeight > 0 && ((float) scrollY / totalHeight) >= 0.95f) {
                triggerAutoNext();
            }
        });
    }

    // إضافة listener على scroll لكشف نهاية الفصل
    private void setupScrollProgress(View progressBar, FrameLayout.LayoutParams pbParams, FrameLayout root) {
        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (pagesContainer.getHeight() == 0) return;
            int scrollY     = scrollView.getScrollY();
            int totalHeight = pagesContainer.getHeight() - scrollView.getHeight();
            if (totalHeight <= 0) return;

            float progress = (float) scrollY / totalHeight;

            // تحديث شريط التقدم
            int newWidth = (int) (root.getWidth() * progress);
            pbParams.width = newWidth;
            progressBar.setLayoutParams(pbParams);

            // مؤشر الصفحة
            updatePageIndicator(scrollY);

            // الانتقال التلقائي عند 95%
            if (autoNextEnabled && progress >= 0.95f &&
                autoNextScreen.getVisibility() != View.VISIBLE &&
                currentChapterIndex > 0) {
                triggerAutoNext();
            }
        });
    }

    private void triggerAutoNext() {
        if (currentChapterIndex <= 0) return;
        ChapterItem nextCh = chapterList.get(currentChapterIndex - 1);
        autoNextScreen.setVisibility(View.VISIBLE);
        tvAutoNextName.setText(nextCh.name);
        autoNextCountdown = 5;
        tvAutoNextCount.setText(String.valueOf(autoNextCountdown));

        autoNextRunnable = new Runnable() {
            @Override
            public void run() {
                autoNextCountdown--;
                tvAutoNextCount.setText(String.valueOf(autoNextCountdown));
                if (autoNextCountdown <= 0) {
                    doAutoNext();
                } else {
                    autoNextHandler.postDelayed(this, 1000);
                }
            }
        };
        autoNextHandler.postDelayed(autoNextRunnable, 1000);
    }

    private void doAutoNext() {
        cancelAutoNext();
        if (currentChapterIndex > 0) openChapter(currentChapterIndex - 1);
    }

    private void cancelAutoNext() {
        autoNextHandler.removeCallbacks(autoNextRunnable);
        autoNextScreen.setVisibility(View.GONE);
    }

    // ============================================================
    //  التمرير التلقائي
    // ============================================================
    private void toggleAutoScroll(TextView btn) {
        isAutoScrolling = !isAutoScrolling;
        if (isAutoScrolling) {
            btn.setTextColor(COLOR_ACCENT);
            btn.setBackground(makePillDrawable(0x22FF6B00));
            startAutoScroll();
        } else {
            btn.setTextColor(COLOR_HINT);
            btn.setBackground(makePillDrawable(0x22FFFFFF));
            stopAutoScroll();
        }
    }

    private void startAutoScroll() {
        autoScrollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAutoScrolling) return;
                scrollView.smoothScrollBy(0, (int) autoScrollSpeed);
                autoScrollHandler.postDelayed(this, 16);
            }
        };
        autoScrollHandler.post(autoScrollRunnable);
    }

    private void stopAutoScroll() {
        autoScrollHandler.removeCallbacks(autoScrollRunnable);
    }

    // ============================================================
    //  التكبير / التصغير
    // ============================================================
    private void changeZoom(float delta) {
        currentZoom = Math.max(0.5f, Math.min(2.0f, currentZoom + delta));
        applyZoom();
        int pct = Math.round(currentZoom * 100);
        if (tvZoom != null) tvZoom.setText(pct + "%");
    }

    private void applyZoom() {
        for (int i = 0; i < pagesContainer.getChildCount(); i++) {
            View child = pagesContainer.getChildAt(i);
            if (child instanceof FrameLayout) {
                child.setScaleX(currentZoom);
                child.setScaleY(currentZoom);
            }
        }
    }

    // ============================================================
    //  وضع حماية العين
    // ============================================================
    private boolean isEyeCare = false;
    private void toggleEyeCare(TextView btn) {
        isEyeCare = !isEyeCare;
        if (isEyeCare) {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            float[] matrix = {
                0.9f, 0.1f, 0f,   0f, 10f,
                0.1f, 0.85f, 0.05f, 0f, 10f,
                0.05f, 0.1f, 0.7f, 0f,  0f,
                0f,   0f,   0f,   1f,  0f
            };
            cm.set(matrix);
            android.graphics.ColorMatrixColorFilter filter =
                new android.graphics.ColorMatrixColorFilter(cm);
            applyColorFilter(filter);
            btn.setTextColor(COLOR_ACCENT);
            btn.setBackground(makePillDrawable(0x22FF6B00));
        } else {
            applyColorFilter(null);
            btn.setTextColor(COLOR_HINT);
            btn.setBackground(makePillDrawable(0x22FFFFFF));
        }
    }

    private void applyColorFilter(android.graphics.ColorFilter filter) {
        for (int i = 0; i < pagesContainer.getChildCount(); i++) {
            View v = pagesContainer.getChildAt(i);
            if (v instanceof FrameLayout) {
                FrameLayout frame = (FrameLayout) v;
                for (int j = 0; j < frame.getChildCount(); j++) {
                    View child = frame.getChildAt(j);
                    if (child instanceof ImageView) {
                        ((ImageView) child).setColorFilter(filter);
                    }
                }
            }
        }
    }

    // ============================================================
    //  إخفاء / إظهار الواجهة
    // ============================================================
    private void toggleUI() {
        isUIVisible = !isUIVisible;
        float targetAlpha = isUIVisible ? 1f : 0f;
        float startY_top  = isUIVisible ? -uiTopBar.getHeight() : 0;
        float endY_top    = isUIVisible ? 0 : -uiTopBar.getHeight();
        float startY_bot  = isUIVisible ? uiBottomBar.getHeight() : 0;
        float endY_bot    = isUIVisible ? 0 : uiBottomBar.getHeight();

        uiTopBar.animate()
            .translationY(endY_top).alpha(targetAlpha)
            .setDuration(220).start();
        uiBottomBar.animate()
            .translationY(endY_bot).alpha(targetAlpha)
            .setDuration(220).start();
    }

    // ============================================================
    //  مؤشر الصفحة
    // ============================================================
    private Handler pageIndicatorHandler = new Handler(Looper.getMainLooper());
    private void updatePageIndicator(int scrollY) {
        if (imageUrls.isEmpty() || tvPageInfo == null) return;
        int h = pagesContainer.getHeight();
        if (h == 0) return;
        int page = (int) ((float) scrollY / h * imageUrls.size()) + 1;
        page = Math.min(page, imageUrls.size());
        tvPageInfo.setText(page + " / " + imageUrls.size());
        tvPageInfo.setVisibility(View.VISIBLE);
        pageIndicatorHandler.removeCallbacksAndMessages(null);
        pageIndicatorHandler.postDelayed(() -> tvPageInfo.setVisibility(View.GONE), 1500);
    }

    // ============================================================
    //  إيماءات اللمس
    // ============================================================
    private void setupGestures() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                currentZoom *= detector.getScaleFactor();
                currentZoom = Math.max(0.5f, Math.min(2.0f, currentZoom));
                applyZoom();
                int pct = Math.round(currentZoom * 100);
                if (tvZoom != null) tvZoom.setText(pct + "%");
                return true;
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleUI();
                return true;
            }
        });

        scrollView.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            return false;
        });
    }

    @Override
    public void onBackPressed() {
        if (autoNextScreen.getVisibility() == View.VISIBLE) {
            cancelAutoNext();
        } else {
            finish();
        }
    }

    // ============================================================
    //  أدوات مساعدة للـ UI
    // ============================================================
    private TextView makeIconBtn(String text, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(16);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        tv.setOnClickListener(listener);
        return tv;
    }

    private TextView makeTagBtn(String text, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_HINT);
        tv.setTextSize(11);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackground(makePillDrawable(0x22FFFFFF));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        p.setMargins(dp(4), 0, dp(4), 0);
        tv.setLayoutParams(p);
        tv.setOnClickListener(listener);
        return tv;
    }

    private TextView makeNavBtn(String text, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setBackground(makePillDrawable(0x33FFFFFF));
        tv.setOnClickListener(listener);
        return tv;
    }

    private android.graphics.drawable.GradientDrawable makePillDrawable(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(50));
        gd.setColor(color);
        return gd;
    }

    private android.graphics.drawable.Drawable makeCircleDrawable() {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(0x22FF6B00);
        gd.setStroke(dp(3), COLOR_ACCENT);
        return gd;
    }

    // ============================================================
    //  جلب HTML
    // ============================================================
    private String fetchUrl(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    // ============================================================
    //  أدوات مساعدة عامة
    // ============================================================
    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void showError(String msg) {
        LinearLayout errorView = new LinearLayout(this);
        errorView.setOrientation(LinearLayout.VERTICAL);
        errorView.setGravity(android.view.Gravity.CENTER);
        errorView.setMinimumHeight(getResources().getDisplayMetrics().heightPixels);

        TextView tv = new TextView(this);
        tv.setText("⚠️ " + msg);
        tv.setTextColor(0xFFFF4444);
        tv.setTextSize(14);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(20), 0, dp(20), 0);
        errorView.addView(tv);

        pagesContainer.addView(errorView);
    }

    private void showPageError(FrameLayout frame, int pageNum) {
        TextView tv = new TextView(this);
        tv.setText("⚠️ خطأ في تحميل الصفحة " + pageNum);
        tv.setTextColor(0xFFFF4444);
        tv.setTextSize(12);
        tv.setGravity(android.view.Gravity.CENTER);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(200)
        );
        frame.addView(tv, p);
    }
}
