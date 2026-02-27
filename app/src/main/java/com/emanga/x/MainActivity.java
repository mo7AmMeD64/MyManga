
package com.emanga.x;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import android.content.Intent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.webkit.CookieManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.Map;
import android.util.Base64;
import java.security.MessageDigest;

public class MainActivity extends Activity {

    private WebView webView;
    private static final String BASE_URL = "https://olympustaff.com";
    private static final String HF_API = "https://1we323-teemx.hf.space";

    private ExecutorService executor = Executors.newFixedThreadPool(50);
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private Map<String, String> cookies = new HashMap<String, String>();
    private long lastRequestTime = 0;
    private static final long MIN_REQUEST_INTERVAL = 1000;

    // ========== متغيرات نظام التحميل ==========
    private static final String CHANNEL_ID = "manga_download_channel";
    private AtomicInteger notificationId = new AtomicInteger(1000);
    private File downloadFolder;
	private GoogleSignInClient mGoogleSignInClient;
	private static final int RC_SIGN_IN = 9001;      
    private static final String GOOGLE_CLIENT_ID = "124742013888-56c6lavsdm3qjhqhr6dq3i4o04u4gjru.apps.googleusercontent.com";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        // إعداد مجلد التحميلات
        setupDownloadFolder();

        createNotificationChannel();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setUserAgentString(getUserAgent());
		GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
			.requestIdToken(GOOGLE_CLIENT_ID) // تمت إضافة المعرف هنا
			.requestEmail()
			.requestProfile()
			.build();
		mGoogleSignInClient = GoogleSignIn.getClient(this, gso);
		
		

        CookieManager.getInstance().setAcceptCookie(true);

        webView.addJavascriptInterface(new WebAppInterface(), "Android");

        loadHTMLFromAssets();
    }

    private void setupDownloadFolder() {
        downloadFolder = new File(getFilesDir(), "manga_downloads");
        if (!downloadFolder.exists()) {
            downloadFolder.mkdirs();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Manga Downloads";
            String description = "Notifications for manga chapter downloads";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }


    private String getChapterHash(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(url.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }

    private void loadHTMLFromAssets() {
        try {
            InputStream is = getAssets().open("index.html");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String html = new String(buffer, "UTF-8");
            webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private String getUserAgent() {
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36";
    }

    private String fixUrl(String url) {
        if (url == null || url.isEmpty()) return BASE_URL;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("/")) return BASE_URL + url;
        return BASE_URL + "/" + url;
    }

    private String fetchUrl(String urlString) throws Exception {
        waitForRateLimit();
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", getUserAgent());

        if (!cookies.isEmpty()) {
            StringBuilder cookieHeader = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (cookieHeader.length() > 0) cookieHeader.append("; ");
                cookieHeader.append(entry.getKey()).append("=").append(entry.getValue());
            }
            conn.setRequestProperty("Cookie", cookieHeader.toString());
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();
        return response.toString();
    }

    private void waitForRateLimit() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastRequest = currentTime - lastRequestTime;
        if (timeSinceLastRequest < MIN_REQUEST_INTERVAL) {
            try { Thread.sleep(MIN_REQUEST_INTERVAL - timeSinceLastRequest); }
            catch (InterruptedException e) { e.printStackTrace(); }
        }
        lastRequestTime = System.currentTimeMillis();
    }

    private String callApi(String endpoint) {
        try {
            URL url = new URL(HF_API + endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close();
            return response.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public class WebAppInterface {

        @JavascriptInterface
        public void restartApp() {
            mainHandler.post(new Runnable() {
					@Override
					public void run() {
						webView.clearHistory(); 
						webView.loadUrl("file:///android_asset/index.html");
					}
				});
        }
		
		@JavascriptInterface
		public void startGoogleSignIn() {
			mainHandler.post(new Runnable() {
					@Override
					public void run() {
						Intent signInIntent = mGoogleSignInClient.getSignInIntent();
						startActivityForResult(signInIntent, RC_SIGN_IN);
					}
				});
		}
		

        @JavascriptInterface
        public void getHome(final int page) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String url = BASE_URL + "/series/" + (page > 1 ? "?page=" + page : "");
							String html = fetchUrl(url);
							Document doc = Jsoup.parse(html);
							Elements items = doc.select("div.listupd div.bsx");
							JSONArray mangaArray = new JSONArray();
							for (Element item : items) {
								JSONObject manga = new JSONObject();
								manga.put("title", item.select("a").attr("title"));
								manga.put("link", item.select("a").attr("href"));
								Element img = item.select("img").first();
								if (img != null) {
									String imgUrl = img.hasAttr("data-src") ? img.absUrl("data-src") : img.absUrl("src");
									manga.put("img", imgUrl);
								}
								mangaArray.put(manga);
							}
							sendDataToJS("onHomeLoaded", mangaArray.toString());
						} catch (Exception e) { sendError("Error: " + e.getMessage()); }
					}
				});
        }

        @JavascriptInterface
        public void search(final String query) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String url = BASE_URL + "/ajax/search?keyword=" + URLEncoder.encode(query, "UTF-8");
							String html = fetchUrl(url);
							Document doc = Jsoup.parse(html);
							Elements items = doc.select("a.items-center");
							JSONArray mangaArray = new JSONArray();
							for (Element item : items) {
								JSONObject manga = new JSONObject();
								manga.put("title", item.select("h4").text());
								manga.put("link", item.absUrl("href"));
								Element img = item.select("img").first();
								if (img != null) manga.put("img", img.absUrl("src"));
								mangaArray.put(manga);
							}
							sendDataToJS("onHomeLoaded", mangaArray.toString());
						} catch (Exception e) { sendError("Search Error"); }
					}
				});
        }

        @JavascriptInterface
        public void getDetails(final String mangaUrl) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String baseUrl = fixUrl(mangaUrl);
							String html = fetchUrl(baseUrl);
							Document doc = Jsoup.parse(html);
							JSONObject details = new JSONObject();

							Element titleEl = doc.select("div.author-info-title h1").first();
							if (titleEl != null) details.put("title", titleEl.text());

							Element descEl = doc.select("div.review-content").first();
							if (descEl != null) details.put("story", descEl.text());

							Elements genreEls = doc.select("div.review-author-info a");
							JSONArray genresArray = new JSONArray();
							for (Element g : genreEls) genresArray.put(g.text());
							details.put("genres", genresArray);

							Element imgElement = doc.select("div.text-right img").first();
							if (imgElement != null) details.put("img", imgElement.absUrl("src"));

							JSONArray chaptersArray = new JSONArray();
							int page = 1;
							boolean hasNext = true;
							String currentUrl = baseUrl;

							while (hasNext) {
								if (page > 1) {
									if (baseUrl.contains("?")) currentUrl = baseUrl + "&page=" + page;
									else currentUrl = baseUrl + "?page=" + page;

									String pageHtml = fetchUrl(currentUrl);
									doc = Jsoup.parse(pageHtml);
								}

								Elements chItems = doc.select("div.chapter-card");
								if (chItems.isEmpty()) {
									chItems = doc.select("#chapterlist li, li.wp-manga-chapter");
								}

								if (chItems.isEmpty()) break;

								for (Element item : chItems) {
									JSONObject chapter = new JSONObject();
									Element numEl = item.select("div.chapter-number").first();
									Element titleChapterEl = item.select("div.chapter-title").first();

									String chpNum = numEl != null ? numEl.text() : "";
									String chpTitle = titleChapterEl != null ? titleChapterEl.text() : "";

									String name = "";
									if (!chpNum.isEmpty()) name = chpNum;
									if (!chpTitle.isEmpty()) name = name + (name.isEmpty() ? "" : " - ") + chpTitle;

									if (name.isEmpty()) {
										name = item.select(".chapter-manhwa-title, p").text();
										if(name.isEmpty()) name = item.text(); 
									}

									chapter.put("name", name);

									Element linkEl = item.select("a").first();
									if (linkEl != null) {
										chapter.put("link", linkEl.attr("href"));
									}

									chapter.put("date", item.attr("data-date"));
									chaptersArray.put(chapter);
								}

								hasNext = doc.select("a[rel=next], a.next").size() > 0;
								page++;
								if(page > 50) hasNext = false; 
							}

							details.put("chapters", chaptersArray);
							sendDataToJS("onDetailsLoaded", details.toString());

						} catch (Exception e) {
							e.printStackTrace();
							sendError("Error loading details: " + e.getMessage());
						}
					}
				});
        }

        @JavascriptInterface
        public void getImages(final String chapterUrl) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							// التحقق أولاً من وجود الفصل محلياً
							String hash = getChapterHash(chapterUrl);
							File chapterFile = new File(downloadFolder, hash + ".json");

							if (chapterFile.exists()) {
								// قراءة الملف المحلي
								BufferedReader reader = new BufferedReader(
									new InputStreamReader(new java.io.FileInputStream(chapterFile), "UTF-8"));
								StringBuilder sb = new StringBuilder();
								String line;
								while ((line = reader.readLine()) != null) {
									sb.append(line);
								}
								reader.close();

								sendDataToJS("onImagesLoaded", sb.toString());
								return;
							}

							// إذا لم يكن موجوداً محلياً، جلب من الإنترنت
							String url = fixUrl(chapterUrl);
							String html = fetchUrl(url);
							Document doc = Jsoup.parse(html);
							JSONArray pagesArray = new JSONArray();

							Elements imgs = doc.select("div.image_list canvas[data-src], div.image_list img, .reading-content img, #readerarea img");

							for (Element img : imgs) {
								String imgUrl = "";
								if (img.hasAttr("data-src")) imgUrl = img.absUrl("data-src");
								else if (img.hasAttr("src")) imgUrl = img.absUrl("src");

								if (!imgUrl.isEmpty() && !imgUrl.contains("loader") && !imgUrl.contains("pixel")) {
									pagesArray.put(imgUrl);
								}
							}

							JSONObject result = new JSONObject();
							result.put("images", pagesArray);
							sendDataToJS("onImagesLoaded", result.toString());

						} catch (Exception e) {
							e.printStackTrace();
							sendError("Images Error: " + e.getMessage());
						}
					}
				});
        }


		@JavascriptInterface
		public void downloadChapter(final String chapterUrl, final String mangaTitle, final String chapterName, final String imgUrl) {
			executor.execute(new Runnable() {
					@Override
					public void run() {
						int currentNotifId = notificationId.incrementAndGet();

						try {
							callJS("onDownloadStart", chapterUrl);

							// جلب الصفحة
							String url = fixUrl(chapterUrl);
							String html = fetchUrl(url);
							Document doc = Jsoup.parse(html);

							Elements imgs = doc.select("div.image_list canvas[data-src], div.image_list img, .reading-content img, #readerarea img");

							if (imgs.isEmpty()) {
								callJS("onDownloadError", chapterUrl);
								return;
							}

							JSONArray imageUrls = new JSONArray();
							JSONArray localPaths = new JSONArray();

							String hash = getChapterHash(chapterUrl);
							File chapterDir = new File(downloadFolder, hash);
							if (!chapterDir.exists()) chapterDir.mkdirs();

							int total = imgs.size();
							int downloaded = 0;

							for (Element img : imgs) {
								String src = "";
								if (img.hasAttr("data-src")) src = img.absUrl("data-src");
								else if (img.hasAttr("src")) src = img.absUrl("src");

								if (src.isEmpty() || src.contains("loader") || src.contains("pixel")) {
									continue;
								}

								imageUrls.put(src);
								String fileName = "page_" + downloaded + ".jpg";
								File imageFile = new File(chapterDir, fileName);

								downloadImage(src, imageFile);
								localPaths.put("file://" + imageFile.getAbsolutePath());

								downloaded++;
								final int percent = (downloaded * 100) / total;

								mainHandler.post(new Runnable() {
										@Override
										public void run() {
											webView.evaluateJavascript("onDownloadProgress('" + chapterUrl + "', " + percent + ");", null);
										}
									});

								showDownloadNotification(currentNotifId, mangaTitle, chapterName, percent);
							}

							// حفظ البيانات الوصفية
							JSONObject chapterData = new JSONObject();
							chapterData.put("images", localPaths);
							chapterData.put("title", mangaTitle);
							chapterData.put("name", chapterName);
							chapterData.put("url", chapterUrl);
							chapterData.put("img", imgUrl); // حفظنا الصورة أيضاً

							File metaFile = new File(downloadFolder, hash + ".json");
							FileOutputStream fos = new FileOutputStream(metaFile);
							fos.write(chapterData.toString().getBytes("UTF-8"));
							fos.close();

							showCompletedNotification(currentNotifId, mangaTitle, chapterName);

							// === التغيير الجوهري هنا: إرسال كافة البيانات للجافا سكريبت ===
							// نقوم بتشفير البيانات لإرسالها بشكل آمن
							JSONObject completionData = new JSONObject();
							completionData.put("url", chapterUrl);
							completionData.put("title", mangaTitle);
							completionData.put("name", chapterName);
							completionData.put("img", imgUrl);

							String jsonStr = completionData.toString();
							final String b64 = Base64.encodeToString(jsonStr.getBytes("UTF-8"), Base64.NO_WRAP);

							mainHandler.post(new Runnable() {
									@Override
									public void run() {
										webView.evaluateJavascript("onDownloadComplete('" + b64 + "');", null);
									}
								});

						} catch (Exception e) {
							e.printStackTrace();
							callJS("onDownloadError", chapterUrl);
							cancelNotification(currentNotifId);
						}
					}
				});
		}

        @JavascriptInterface
        public void deleteChapter(final String chapterUrl) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String hash = getChapterHash(chapterUrl);

							// حذف مجلد الصور
							File chapterDir = new File(downloadFolder, hash);
							if (chapterDir.exists()) {
								deleteRecursive(chapterDir);
							}

							// حذف ملف البيانات
							File metaFile = new File(downloadFolder, hash + ".json");
							if (metaFile.exists()) {
								metaFile.delete();
							}

						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				});
        }


        private void deleteRecursive(File fileOrDirectory) {
            if (fileOrDirectory.isDirectory()) {
                for (File child : fileOrDirectory.listFiles()) {
                    deleteRecursive(child);
                }
            }
            fileOrDirectory.delete();
        }


        private void downloadImage(String urlString, File outputFile) throws Exception {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", getUserAgent());

            InputStream input = new BufferedInputStream(conn.getInputStream());
            FileOutputStream output = new FileOutputStream(outputFile);

            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            output.flush();
            output.close();
            input.close();
        }

        // إشعارات التحميل
        private void showDownloadNotification(int id, String manga, String chapter, int progress) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(MainActivity.this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("تحميل: " + manga)
                .setContentText(chapter)
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

            NotificationManagerCompat.from(MainActivity.this).notify(id, builder.build());
        }

        private void showCompletedNotification(int id, String manga, String chapter) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(MainActivity.this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("اكتمل التحميل")
                .setContentText(manga + " - " + chapter)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true);

            NotificationManagerCompat.from(MainActivity.this).notify(id, builder.build());
        }

        private void cancelNotification(int id) {
            NotificationManagerCompat.from(MainActivity.this).cancel(id);
        }

        // باقي الدوال (News, Rating, Comments, Auth)
        @JavascriptInterface
        public void getNews() {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String jsonResponse = callApi("/get-news");
							if (jsonResponse != null) {
								sendDataToJS("onNewsLoaded", jsonResponse);
							} else {
								sendDataToJS("onNewsLoaded", "[]");
							}
						} catch (Exception e) { e.printStackTrace(); }
					}
				});
        }

        @JavascriptInterface
        public void getRating(final String url) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String apiUrl = "/get-rating?url=" + URLEncoder.encode(url, "UTF-8");
							String jsonResponse = callApi(apiUrl);
							if (jsonResponse != null) {
								sendDataToJS("onRatingLoaded", jsonResponse);
							}
						} catch (Exception e) { e.printStackTrace(); }
					}
				});
        }

        @JavascriptInterface
        public void sendRatingAuth(final String url, final String score, final String title, final String img, final String email) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String apiUrl = "/post-rating?url=" + URLEncoder.encode(url, "UTF-8") +
								"&score=" + URLEncoder.encode(score, "UTF-8") +
								"&email=" + URLEncoder.encode(email, "UTF-8") +
								"&title=" + URLEncoder.encode(title, "UTF-8") +
								"&img=" + URLEncoder.encode(img, "UTF-8");

							callApi(apiUrl);
							getRating(url); 
						} catch (Exception e) { e.printStackTrace(); }
					}
				});
        }

        @JavascriptInterface
        public void getComments(final String url) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String apiUrl = "/get-comments?url=" + java.net.URLEncoder.encode(url, "UTF-8");
							String jsonResponse = callApi(apiUrl);

							if (jsonResponse != null) {
								sendDataToJS("onCommentsLoaded", jsonResponse);
							} else {
								sendDataToJS("onCommentsLoaded", "[]");
							}
						} catch (Exception e) {
							e.printStackTrace();
							sendDataToJS("onCommentsLoaded", "[]");
						}
					}
				});
        }

        @JavascriptInterface
        public void sendComment(final String url, final String name, final String text) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String apiUrl = "/post-comment?url=" + java.net.URLEncoder.encode(url, "UTF-8") +
								"&name=" + java.net.URLEncoder.encode(name, "UTF-8") +
								"&text=" + java.net.URLEncoder.encode(text, "UTF-8");
							callApi(apiUrl);
							getComments(url);
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				});
        }

        @JavascriptInterface
        public void login(final String email, final String password) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String apiUrl = "/login?email=" + URLEncoder.encode(email, "UTF-8") +
								"&password=" + URLEncoder.encode(password, "UTF-8");
							String response = callApi(apiUrl);
							if(response != null) {
								sendDataToJS("onLoginResponse", response);
							}
						} catch (Exception e) { e.printStackTrace(); }
					}
				});
        }

        @JavascriptInterface
        public void signup(final String email, final String password, final String name) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String apiUrl = "/signup?email=" + URLEncoder.encode(email, "UTF-8") +
								"&password=" + URLEncoder.encode(password, "UTF-8") +
								"&name=" + URLEncoder.encode(name, "UTF-8");
							String response = callApi(apiUrl);
							if(response != null) {
								sendDataToJS("onAuthResponse", response);
							}
						} catch (Exception e) { e.printStackTrace(); }
					}
				});
        }

        @JavascriptInterface
        public void syncFav(final String email, final String favsJson) {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String apiUrl = "/sync-fav?email=" + URLEncoder.encode(email, "UTF-8") +
								"&favs=" + URLEncoder.encode(favsJson, "UTF-8");
							callApi(apiUrl);
						} catch (Exception e) { e.printStackTrace(); }
					}
				});
        }

        @JavascriptInterface
        public void toggleFullscreen(final boolean enable) {
            mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (enable) {
							getWindow().getDecorView().setSystemUiVisibility(
								View.SYSTEM_UI_FLAG_FULLSCREEN |
								View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
								View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
						} else {
							getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
						}
					}
				});
        }

        @JavascriptInterface 
        public void exit() { finish(); }


        @JavascriptInterface
public void getFav(final String email) {
    executor.execute(new Runnable() {
        @Override
        public void run() {
            try {
                String apiUrl = "/get-fav?email=" + URLEncoder.encode(email, "UTF-8");
                String response = callApi(apiUrl);
                if (response != null) {
                    sendDataToJS("onFavLoaded", response);
                } else {
                    sendDataToJS("onFavLoaded", "[]");
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    });
}

@JavascriptInterface
public void googleLoginToServer(final String email, final String name) {
    executor.execute(new Runnable() {
        @Override
        public void run() {
            try {
                // تسجيل/إنشاء حساب تلقائي بـ Google
                String apiUrl = "/google-login?email=" + URLEncoder.encode(email, "UTF-8") +
                    "&name=" + URLEncoder.encode(name, "UTF-8");
                String response = callApi(apiUrl);
                if (response != null) {
                    sendDataToJS("onGoogleServerLogin", response);
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    });
}
        @JavascriptInterface
        public void getTop() {
            executor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							String jsonResponse = callApi("/get-top");
							if (jsonResponse != null) {
								sendDataToJS("onTopLoaded", jsonResponse);
							} else {
								sendDataToJS("onTopLoaded", "[]");
							}
						} catch (Exception e) { e.printStackTrace(); }
					}
				});
        }

        // دالة مساعدة لاستدعاء JS بسرعة
        private void callJS(final String funcName, final String param) {
            mainHandler.post(new Runnable() {
					@Override
					public void run() {
						webView.evaluateJavascript(funcName + "('" + param + "');", null);
					}
				});
        }

        private void sendDataToJS(final String functionName, final String jsonData) {
            try {
                final String b64 = android.util.Base64.encodeToString(
                    jsonData.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                mainHandler.post(new Runnable() {
						@Override
						public void run() {
							webView.evaluateJavascript(functionName + "('" + b64 + "');", null);
						}
					});
            } catch (Exception e) { e.printStackTrace(); }
        }

        private void sendError(final String error) {
            mainHandler.post(new Runnable() {
					@Override
					public void run() {
						webView.evaluateJavascript(
							"showToast('" + error.replace("'", "\\'") + "');", null);
					}
				});
        }
    }

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		// النتيجة العائدة من نافذة Google
		if (requestCode == RC_SIGN_IN) {
			Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
			handleSignInResult(task);
		}
	}
	
	private void handleSignInResult(Task<GoogleSignInAccount> completedTask) {
        try {
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);

            String email = account.getEmail();
            String name = account.getDisplayName();

            JSONObject userData = new JSONObject();
            userData.put("email", email);
            userData.put("name", name);

            final String b64 = android.util.Base64.encodeToString(
                userData.toString().getBytes("UTF-8"),
                android.util.Base64.NO_WRAP
            );

            mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript("onGoogleLoginSuccess('" + b64 + "');", null);
                    }
                });

        } catch (ApiException e) {
            final int code = e.getStatusCode();
            final String reason;
            switch (code) {
                case 10: reason = "DEVELOPER_ERROR - SHA1 او Package name خطأ"; break;
                case 7:  reason = "NETWORK_ERROR - لا يوجد اتصال"; break;
                case 12500: reason = "Google Play Services قديم"; break;
                case 12501: reason = "المستخدم ألغى تسجيل الدخول"; break;
                case 12502: reason = "تم تسجيل الدخول مسبقاً"; break;
                case 4:  reason = "SIGN_IN_REQUIRED"; break;
                default: reason = "خطأ غير معروف";
            }
            final String msg = "كود " + code + ": " + reason;
            mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript("showToast('" + msg + "');", null);
                    }
                });
        } catch (Throwable e) {
            final String msg = "استثناء: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript("showToast('" + msg + "');", null);
                    }
                });
        }
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("handleBack();", null);
    }
}




