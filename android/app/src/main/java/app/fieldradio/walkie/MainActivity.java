package app.fieldradio.walkie;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 현장 무전기 앱: 웹페이지(voice.html)를 앱 안에서 열고,
 * 통신 중에는 "마이크 포그라운드 서비스"를 켜서 화면이 꺼져도 통신이 유지되게 합니다.
 */
public class MainActivity extends Activity {
    static final String HOST = "prolinu73-maker.github.io";
    static final String START_URL = "https://" + HOST + "/radio/voice.html";
    private static final int REQ_AUDIO = 11;
    private static final int REQ_NOTI = 12;
    private static final String BG = "#070B14";

    private WebView web;
    private PermissionRequest pendingPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window win = getWindow();
        win.setStatusBarColor(Color.parseColor(BG));
        win.setNavigationBarColor(Color.parseColor(BG));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(BG));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " FieldRadioApp/1.0");

        web.addJavascriptInterface(new Bridge(), "AndroidKeeper");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost()) && "https".equals(u.getScheme())) {
                    return false; // 우리 페이지는 앱 안에서 계속 엽니다
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u)); // 그 밖의 주소는 기본 브라우저로
                } catch (Exception e) {
                    // 열 수 있는 앱이 없으면 무시
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showOffline(view);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handlePermission(request);
                    }
                });
            }
        });

        web.loadUrl(START_URL);

        // 처음 실행할 때: 알림 허용 → 배터리 제한 해제 순서로 안내합니다
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
        } else {
            askBatteryExemption();
        }
    }

    private void showOffline(WebView view) {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='background:#070b14;color:#e8eef7;font-family:sans-serif;text-align:center;padding:90px 24px'>"
                + "<h2>인터넷에 연결되어 있지 않습니다</h2>"
                + "<p style='color:#8794ab'>연결을 확인한 뒤 다시 시도해 주세요.</p>"
                + "<button style='font-size:18px;padding:14px 26px;border:0;border-radius:14px;background:#34d399;font-weight:bold' "
                + "onclick=\"location.href='" + START_URL + "'\">다시 시도</button></body></html>";
        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    /** 웹페이지가 마이크를 요청하면 안드로이드 마이크 권한을 확인한 뒤 허용합니다. */
    private void handlePermission(PermissionRequest request) {
        boolean wantsAudio = false;
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                wantsAudio = true;
            }
        }
        Uri origin = request.getOrigin();
        if (!wantsAudio || origin == null || !HOST.equals(origin.getHost())) {
            request.deny();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            if (pendingPermission != null) {
                pendingPermission.deny();
            }
            pendingPermission = request;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            if (pendingPermission != null) {
                boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
                if (ok) {
                    pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingPermission.deny();
                }
                pendingPermission = null;
            }
        } else if (requestCode == REQ_NOTI) {
            askBatteryExemption();
        }
    }

    /** 화면이 꺼져도 통신이 이어지도록 배터리 사용 제한을 풀어 달라고 한 번만 안내합니다. */
    private void askBatteryExemption() {
        SharedPreferences sp = getSharedPreferences("radio", Context.MODE_PRIVATE);
        if (sp.getBoolean("batteryAsked", false)) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        sp.edit().putBoolean("batteryAsked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("화면이 꺼져도 통신하려면")
                .setMessage("배터리 사용 제한을 풀어야 화면이 꺼진 뒤에도 통신이 끊기지 않습니다.\n다음 화면에서 '허용'을 눌러 주세요.")
                .setPositiveButton("설정으로 이동", (dialog, which) -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        // 설정 화면을 열 수 없으면 무시
                    }
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    /** 웹페이지(voice.html)에서 호출하는 기능: AndroidKeeper.start(...) / stop() */
    private class Bridge {
        @JavascriptInterface
        public boolean start(String title, String text) {
            try {
                Intent i = new Intent(MainActivity.this, VoiceService.class);
                i.setAction(VoiceService.ACTION_START);
                i.putExtra("title", title);
                i.putExtra("text", text);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(i);
                } else {
                    startService(i);
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void stop() {
            try {
                stopService(new Intent(MainActivity.this, VoiceService.class));
            } catch (Exception e) {
                // 이미 멈춘 경우
            }
        }

        @JavascriptInterface
        public String version() {
            return "1.0";
        }
    }

    /** 알림창의 [종료] 버튼을 누르면 앱을 완전히 끝냅니다. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("exit", false)) {
            stopService(new Intent(this, VoiceService.class));
            finishAndRemoveTask();
        }
    }

    /** 뒤로 가기를 눌러도 앱을 끝내지 않고 뒤로 보내 두어 통신을 유지합니다. */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, VoiceService.class));
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
