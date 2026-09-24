package com.ionicframework.cordova.webview;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.apache.cordova.ConfigXmlParser;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPreferences;
import org.apache.cordova.CordovaResourceApi;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CordovaWebViewEngine;
import org.apache.cordova.NativeToJsMessageQueue;
import org.apache.cordova.PluginManager;
import org.apache.cordova.engine.SystemWebViewClient;
import org.apache.cordova.engine.SystemWebViewEngine;
import org.apache.cordova.engine.SystemWebView;

public class IonicWebViewEngine extends SystemWebViewEngine {
  public static final String TAG = "IonicWebViewEngine";

  private WebViewLocalServer localServer;
  private String CDV_LOCAL_SERVER;
  private String scheme;
  private static final String LAST_BINARY_VERSION_CODE = "lastBinaryVersionCode";
  private static final String LAST_BINARY_VERSION_NAME = "lastBinaryVersionName";
  private static final String PREF_FORCE_SOFTWARE_RENDERING = "IonicWebViewForceSoftwareRendering";
  private static final String PREF_FORCE_REPAINT = "IonicWebViewForceRepaint";
  private static final String PREF_ENABLE_RENDER_DIAGNOSTICS = "IonicWebViewEnableRenderDiagnostics";
  private static final String PREF_ENABLE_VISUAL_DIAGNOSTICS = "IonicWebViewEnableVisualDiagnostics";
  private static final String NATIVE_DIAGNOSTIC_TAG = "__ionic_native_diagnostic";
  private static final int NATIVE_DIAGNOSTIC_MAX_ATTACH_RETRIES = 5;
  private static final int NATIVE_DIAGNOSTIC_RETRY_DELAY_MS = 75;
  private boolean forceRepaint;
  private boolean enableRenderDiagnostics;
  private boolean enableVisualDiagnostics;

  /**
   * Used when created via reflection.
   */
  public IonicWebViewEngine(Context context, CordovaPreferences preferences) {
    super(new SystemWebView(context), preferences);
    Log.d(TAG, "Ionic Web View Engine Starting Right Up 1...");
  }

  public IonicWebViewEngine(SystemWebView webView) {
    super(webView, null);
    Log.d(TAG, "Ionic Web View Engine Starting Right Up 2...");
  }

  public IonicWebViewEngine(SystemWebView webView, CordovaPreferences preferences) {
    super(webView, preferences);
    Log.d(TAG, "Ionic Web View Engine Starting Right Up 3...");
  }

  @Override
  public void init(CordovaWebView parentWebView, CordovaInterface cordova, final CordovaWebViewEngine.Client client,
                   CordovaResourceApi resourceApi, PluginManager pluginManager,
                   NativeToJsMessageQueue nativeToJsMessageQueue) {
    ConfigXmlParser parser = new ConfigXmlParser();
    parser.parse(cordova.getActivity());

    String hostname = preferences.getString("Hostname", "localhost");
    scheme = preferences.getString("Scheme", "http");
    CDV_LOCAL_SERVER = scheme + "://" + hostname;

    localServer = new WebViewLocalServer(cordova.getActivity(), hostname, true, parser, scheme);
    localServer.hostAssets("www");

    webView.setWebViewClient(new ServerClient(this, parser));

    super.init(parentWebView, cordova, client, resourceApi, pluginManager, nativeToJsMessageQueue);
    forceRepaint = preferences.getBoolean(PREF_FORCE_REPAINT, false);
    enableRenderDiagnostics = preferences.getBoolean(PREF_ENABLE_RENDER_DIAGNOSTICS, false);
    enableVisualDiagnostics = preferences.getBoolean(PREF_ENABLE_VISUAL_DIAGNOSTICS, false);

    boolean forceSoftwareRendering = preferences.getBoolean(PREF_FORCE_SOFTWARE_RENDERING, false);
    if (forceSoftwareRendering) {
      webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
      Log.w(TAG, "IonicWebViewForceSoftwareRendering enabled: forcing WebView software rendering. This may reduce performance.");
    }

    final WebSettings settings = webView.getSettings();
    int mode = preferences.getInteger("MixedContentMode", 0);
    settings.setMixedContentMode(mode);

    logRenderState("init", webView.getUrl());
    if (enableVisualDiagnostics) {
      ensureNativeDiagnosticMarker("init", 0);
    }
    webView.postDelayed(new Runnable() {
      @Override
      public void run() {
        logRenderState("init_delayed_3s", webView.getUrl());
      }
    }, 3000);

    SharedPreferences prefs = cordova.getActivity().getApplicationContext().getSharedPreferences(IonicWebView.WEBVIEW_PREFS_NAME, Activity.MODE_PRIVATE);
    String path = prefs.getString(IonicWebView.CDV_SERVER_PATH, null);
    if (!isDeployDisabled() && !isNewBinary() && path != null && !path.isEmpty()) {
      setServerBasePath(path);
    }

    boolean setAsServiceWorkerClient = preferences.getBoolean("ResolveServiceWorkerRequests", false);

    if (setAsServiceWorkerClient) {
        ServiceWorkerController controller = ServiceWorkerController.getInstance();
        controller.setServiceWorkerClient(new ServiceWorkerClient(){
            @Override
            public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                return localServer.shouldInterceptRequest(request.getUrl(), request);
            }
        });
    }
  }

  private void logRenderState(String eventName, String url) {
    if (!enableRenderDiagnostics) {
      return;
    }

    String webViewPackage = "unavailable";
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      try {
        PackageInfo webViewPackageInfo = WebView.getCurrentWebViewPackage();
        if (webViewPackageInfo != null) {
          String packageName = webViewPackageInfo.packageName != null ? webViewPackageInfo.packageName : "unknown";
          String packageVersion = webViewPackageInfo.versionName != null ? webViewPackageInfo.versionName : "unknown";
          webViewPackage = packageName + "@" + packageVersion;
        } else {
          webViewPackage = "null";
        }
      } catch (Exception ex) {
        webViewPackage = "error:" + ex.getClass().getSimpleName();
      }
    } else {
      webViewPackage = "unsupported_api";
    }

    Object parent = webView.getParent();
    ViewGroup.LayoutParams params = webView.getLayoutParams();
    String parentDescription = parent == null ? "null" : parent.getClass().getName() + ":" + parent.toString();
    String paramsDescription;
    if (params == null) {
      paramsDescription = "null";
    } else if (params instanceof FrameLayout.LayoutParams) {
      FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) params;
      paramsDescription = frameParams.getClass().getName()
          + "(width=" + frameParams.width
          + ",height=" + frameParams.height
          + ",leftMargin=" + frameParams.leftMargin
          + ",topMargin=" + frameParams.topMargin
          + ",rightMargin=" + frameParams.rightMargin
          + ",bottomMargin=" + frameParams.bottomMargin
          + ",gravity=" + frameParams.gravity
          + ")";
    } else {
      paramsDescription = params.getClass().getName() + "(width=" + params.width + ",height=" + params.height + ")";
    }

    Log.d(TAG, "RenderState"
        + " event=" + eventName
        + " url=" + (url != null ? url : "null")
        + " sdk=" + Build.VERSION.SDK_INT
        + " manufacturer=" + Build.MANUFACTURER
        + " model=" + Build.MODEL
        + " webViewPackage=" + webViewPackage
        + " attached=" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ? webView.isAttachedToWindow() : "unsupported_api")
        + " shown=" + webView.isShown()
        + " visibility=" + webView.getVisibility()
        + " width=" + webView.getWidth()
        + " height=" + webView.getHeight()
        + " x=" + webView.getX()
        + " y=" + webView.getY()
        + " alpha=" + webView.getAlpha()
        + " hardwareAccelerated=" + webView.isHardwareAccelerated()
        + " layerType=" + layerTypeToString(webView.getLayerType())
        + " parent=" + parentDescription
        + " layoutParams=" + paramsDescription);
  }

  private String layerTypeToString(int layerType) {
    if (layerType == View.LAYER_TYPE_HARDWARE) {
      return "hardware";
    }
    if (layerType == View.LAYER_TYPE_SOFTWARE) {
      return "software";
    }
    return "none";
  }

  private void ensureNativeDiagnosticMarker(final String trigger, final int attempt) {
    if (!enableVisualDiagnostics || webView == null) {
      return;
    }

    webView.post(new Runnable() {
      @Override
      public void run() {
        if (!enableVisualDiagnostics || webView == null) {
          return;
        }

        Object parentObject = webView.getParent();
        boolean attached = Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT || webView.isAttachedToWindow();
        if (!attached || parentObject == null) {
          if (attempt < NATIVE_DIAGNOSTIC_MAX_ATTACH_RETRIES) {
            final int nextAttempt = attempt + 1;
            webView.postDelayed(new Runnable() {
              @Override
              public void run() {
                if (enableVisualDiagnostics && webView != null) {
                  ensureNativeDiagnosticMarker(trigger, nextAttempt);
                } else if (nextAttempt >= NATIVE_DIAGNOSTIC_MAX_ATTACH_RETRIES) {
                  Log.w(TAG, "Native diagnostic marker skipped (" + trigger + "): parent unavailable after retries.");
                }
              }
            }, NATIVE_DIAGNOSTIC_RETRY_DELAY_MS);
          } else {
            Log.w(TAG, "Native diagnostic marker skipped (" + trigger + "): parent unavailable or WebView detached.");
          }
          return;
        }

        if (!(parentObject instanceof FrameLayout)) {
          Log.w(TAG, "Native diagnostic marker skipped (" + trigger + "): parent is "
              + parentObject.getClass().getName() + ", expected FrameLayout.");
          return;
        }

        final FrameLayout parent = (FrameLayout) parentObject;
        View existingView = parent.findViewWithTag(NATIVE_DIAGNOSTIC_TAG);
        TextView marker = null;
        if (existingView instanceof TextView) {
          marker = (TextView) existingView;
        } else if (existingView != null) {
          parent.removeView(existingView);
          Log.w(TAG, "Native diagnostic marker replaced (" + trigger + "): non-TextView with marker tag found.");
        }

        int horizontalMarginPx = dpToPx(8);
        int bottomMarginPx = dpToPx(12);
        int minHeightPx = dpToPx(56);

        if (marker == null) {
          marker = new TextView(webView.getContext());
          marker.setTag(NATIVE_DIAGNOSTIC_TAG);
          marker.setText("NATIVE VIEW IS RENDERING");
          marker.setTextColor(Color.WHITE);
          marker.setBackgroundColor(Color.rgb(0, 160, 0));
          marker.setTextSize(16);
          marker.setGravity(Gravity.CENTER);
          marker.setVisibility(View.VISIBLE);
          FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              minHeightPx
          );
          params.gravity = Gravity.BOTTOM;
          params.leftMargin = horizontalMarginPx;
          params.rightMargin = horizontalMarginPx;
          params.bottomMargin = bottomMarginPx;
          parent.addView(marker, params);
          Log.w(TAG, "Native diagnostic marker added (" + trigger + ") attempt=" + attempt);
        } else {
          marker.setText("NATIVE VIEW IS RENDERING");
          marker.setTextColor(Color.WHITE);
          marker.setBackgroundColor(Color.rgb(0, 160, 0));
          marker.setTextSize(16);
          marker.setGravity(Gravity.CENTER);
          marker.setVisibility(View.VISIBLE);
          FrameLayout.LayoutParams params;
          ViewGroup.LayoutParams existingParams = marker.getLayoutParams();
          if (existingParams instanceof FrameLayout.LayoutParams) {
            params = (FrameLayout.LayoutParams) existingParams;
          } else {
            params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minHeightPx
            );
          }
          params.width = ViewGroup.LayoutParams.MATCH_PARENT;
          params.height = minHeightPx;
          params.gravity = Gravity.BOTTOM;
          params.leftMargin = horizontalMarginPx;
          params.rightMargin = horizontalMarginPx;
          params.bottomMargin = bottomMarginPx;
          marker.setLayoutParams(params);
          Log.w(TAG, "Native diagnostic marker reused and brought to front (" + trigger + ").");
        }

        marker.bringToFront();
        parent.requestLayout();
        parent.invalidate();
        final TextView layoutMarker = marker;
        parent.post(new Runnable() {
          @Override
          public void run() {
            Log.w(TAG, "Native diagnostic marker layout (" + trigger + "):"
                + " shown=" + layoutMarker.isShown()
                + " visibility=" + layoutMarker.getVisibility()
                + " width=" + layoutMarker.getWidth()
                + " height=" + layoutMarker.getHeight()
                + " x=" + layoutMarker.getX()
                + " y=" + layoutMarker.getY());
          }
        });
      }
    });
  }

  private int dpToPx(int dp) {
    float density = webView.getContext().getResources().getDisplayMetrics().density;
    return Math.max(1, Math.round(dp * density));
  }

  private void runDomDiagnostics(WebView view, boolean includeVisualMarker) {
    if (view == null) {
      return;
    }

    String script = "(function() {"
        + "  try {"
        + "    var includeMarker = " + (includeVisualMarker ? "true" : "false") + ";"
        + "    var markerId = '__ionic_webview_diagnostic';"
        + "    var doc = document;"
        + "    var root = doc && (doc.body || doc.documentElement);"
        + "    var body = doc ? doc.body : null;"
        + "    var html = doc ? doc.documentElement : null;"
        + "    var marker = includeMarker && doc ? doc.getElementById(markerId) : null;"
        + "    if (includeMarker && !marker && doc && doc.createElement) {"
        + "      marker = doc.createElement('div');"
        + "      marker.id = markerId;"
        + "    }"
        + "    if (includeMarker && marker) {"
        + "      marker.textContent = 'WEBVIEW CONTENT IS RENDERING';"
        + "      marker.setAttribute('aria-hidden', 'true');"
        + "      marker.style.cssText = 'position:fixed!important;left:8px!important;right:8px!important;top:8px!important;min-height:48px!important;"
        + "display:block!important;visibility:visible!important;opacity:1!important;z-index:2147483647!important;background:#ff0000!important;color:#ffffff!important;"
        + "font-size:20px!important;line-height:1.25!important;font-weight:700!important;text-align:center!important;padding:12px 8px!important;box-sizing:border-box!important;"
        + "pointer-events:none!important;margin:0!important;transform:none!important;max-width:none!important;';"
        + "      if (!marker.parentNode && root) {"
        + "        root.appendChild(marker);"
        + "      }"
        + "    }"
        + "    function safeClassName(el) {"
        + "      if (!el || !el.className) { return ''; }"
        + "      if (typeof el.className === 'string') { return el.className; }"
        + "      if (typeof el.className.baseVal === 'string') { return el.className.baseVal; }"
        + "      return '';"
        + "    }"
        + "    function readStyle(el) {"
        + "      if (!el || !window.getComputedStyle) { return null; }"
        + "      var style = window.getComputedStyle(el);"
        + "      return {"
        + "        display: style ? style.display : null,"
        + "        visibility: style ? style.visibility : null,"
        + "        opacity: style ? style.opacity : null,"
        + "        backgroundColor: style ? style.backgroundColor : null,"
        + "        position: style ? style.position : null,"
        + "        zIndex: style ? style.zIndex : null"
        + "      };"
        + "    }"
        + "    function markerRect(el) {"
        + "      if (!el || !el.getBoundingClientRect || !el.parentNode) { return null; }"
        + "      var rect = el.getBoundingClientRect();"
        + "      var width = typeof rect.width === 'number' ? rect.width : (rect.right - rect.left);"
        + "      var height = typeof rect.height === 'number' ? rect.height : (rect.bottom - rect.top);"
        + "      return {"
        + "        left: rect.left,"
        + "        top: rect.top,"
        + "        right: rect.right,"
        + "        bottom: rect.bottom,"
        + "        width: width,"
        + "        height: height"
        + "      };"
        + "    }"
        + "    function elementSummary(el) {"
        + "      if (!el) { return null; }"
        + "      var style = readStyle(el) || {};"
        + "      return {"
        + "        tag: el.tagName || null,"
        + "        id: el.id || null,"
        + "        className: safeClassName(el) || null,"
        + "        display: style.display || null,"
        + "        visibility: style.visibility || null,"
        + "        opacity: style.opacity || null,"
        + "        backgroundColor: style.backgroundColor || null,"
        + "        position: style.position || null,"
        + "        zIndex: style.zIndex || null"
        + "      };"
        + "    }"
        + "    var viewportWidth = window.innerWidth || 0;"
        + "    var viewportHeight = window.innerHeight || 0;"
        + "    var centerX = Math.floor(viewportWidth / 2);"
        + "    var centerY = Math.floor(viewportHeight / 2);"
        + "    var centerElement = doc && doc.elementFromPoint ? doc.elementFromPoint(centerX, centerY) : null;"
        + "    var payload = {"
        + "      readyState: doc ? doc.readyState : null,"
        + "      title: doc ? doc.title : null,"
        + "      bodyChildren: body && body.children ? body.children.length : -1,"
        + "      htmlStyle: readStyle(html),"
        + "      bodyStyle: readStyle(body),"
        + "      viewport: { width: viewportWidth, height: viewportHeight },"
        + "      devicePixelRatio: window.devicePixelRatio || null,"
        + "      markerRect: markerRect(marker),"
        + "      centerPoint: { x: centerX, y: centerY },"
        + "      centerElement: elementSummary(centerElement),"
        + "      scroll: {"
        + "        x: window.pageXOffset || 0,"
        + "        y: window.pageYOffset || 0,"
        + "        htmlScrollWidth: html ? html.scrollWidth : null,"
        + "        htmlScrollHeight: html ? html.scrollHeight : null,"
        + "        bodyScrollWidth: body ? body.scrollWidth : null,"
        + "        bodyScrollHeight: body ? body.scrollHeight : null"
        + "      }"
        + "    };"
        + "    return JSON.stringify(payload);"
        + "  } catch (err) {"
        + "    return JSON.stringify({ diagnosticError: String(err && err.message ? err.message : err) });"
        + "  }"
        + "})()";

    try {
      view.evaluateJavascript(script, new ValueCallback<String>() {
        @Override
        public void onReceiveValue(String value) {
          try {
            Log.w(TAG, "DOM diagnostic=" + value);
          } catch (Exception callbackException) {
            Log.e(TAG, "Failed to log DOM diagnostic callback", callbackException);
          }
        }
      });
    } catch (Exception evaluateException) {
      Log.e(TAG, "Failed to run DOM diagnostics", evaluateException);
    }
  }

  private boolean isNewBinary() {
    String versionCode = "";
    String versionName = "";
    SharedPreferences prefs = cordova.getActivity().getApplicationContext().getSharedPreferences(IonicWebView.WEBVIEW_PREFS_NAME, Activity.MODE_PRIVATE);
    String lastVersionCode = prefs.getString(LAST_BINARY_VERSION_CODE, null);
    String lastVersionName = prefs.getString(LAST_BINARY_VERSION_NAME, null);

    try {
      PackageInfo pInfo = this.cordova.getActivity().getPackageManager().getPackageInfo(this.cordova.getActivity().getPackageName(), 0);
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        versionCode = Long.toString(pInfo.getLongVersionCode());
      } else {
        // versionCode is deprecated in API 28, but still needed for API 24-27
        versionCode = Integer.toString(pInfo.versionCode);
      }
      versionName = pInfo.versionName;
    } catch(Exception ex) {
      Log.e(TAG, "Unable to get package info", ex);
    }

    if (!versionCode.equals(lastVersionCode) || !versionName.equals(lastVersionName)) {
      SharedPreferences.Editor editor = prefs.edit();
      editor.putString(LAST_BINARY_VERSION_CODE, versionCode);
      editor.putString(LAST_BINARY_VERSION_NAME, versionName);
      editor.putString(IonicWebView.CDV_SERVER_PATH, "");
      editor.apply();
      return true;
    }
    return false;
  }

  private boolean isDeployDisabled() {
    return preferences.getBoolean("DisableDeploy", false);
  }
  private class ServerClient extends SystemWebViewClient {
    private ConfigXmlParser parser;

    public ServerClient(SystemWebViewEngine parentEngine, ConfigXmlParser parser) {
      super(parentEngine);
      this.parser = parser;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
      return localServer.shouldInterceptRequest(request.getUrl(), request);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
      super.onPageStarted(view, url, favicon);
      String launchUrl = parser.getLaunchUrl();
      if (!launchUrl.contains(WebViewLocalServer.httpsScheme) && !launchUrl.contains(WebViewLocalServer.httpScheme) && url.equals(launchUrl)) {
        view.stopLoading();
        // When using a custom scheme the app won't load if server start url doesn't end in /
        String startUrl = CDV_LOCAL_SERVER;
        if (!scheme.equalsIgnoreCase(WebViewLocalServer.httpsScheme) && !scheme.equalsIgnoreCase(WebViewLocalServer.httpScheme)) {
          startUrl += "/";
        }
        view.loadUrl(startUrl);
      }
    }

    @Override
    public void onPageFinished(WebView view, String url) {
      super.onPageFinished(view, url);
      logRenderState("onPageFinished", url);
      if (enableRenderDiagnostics || enableVisualDiagnostics) {
        if (enableVisualDiagnostics) {
          ensureNativeDiagnosticMarker("onPageFinished", 0);
        }
        runDomDiagnostics(view, enableVisualDiagnostics);
      }
      if (forceRepaint) {
        final WebView finishedView = view;
        Log.w(TAG, "IonicWebViewForceRepaint enabled: applying post-finish repaint workaround.");
        finishedView.post(new Runnable() {
          @Override
          public void run() {
            finishedView.setVisibility(View.INVISIBLE);
            finishedView.requestLayout();
            finishedView.invalidate();
            finishedView.post(new Runnable() {
              @Override
              public void run() {
                finishedView.setVisibility(View.VISIBLE);
                finishedView.requestLayout();
                finishedView.invalidate();
                Log.w(TAG, "IonicWebViewForceRepaint applied.");
                logRenderState("forceRepaintApplied", finishedView.getUrl());
              }
            });
          }
        });
      }
      view.evaluateJavascript(
          "(function() { window.WEBVIEW_SERVER_URL = "
              + JSONObject.quote(CDV_LOCAL_SERVER)
              + "; })()",
          null);
    }
  }

  public void setServerBasePath(String path) {
    localServer.hostFiles(path);
    webView.loadUrl(CDV_LOCAL_SERVER);
  }

  public String getServerBasePath() {
    return this.localServer.getBasePath();
  }
}
