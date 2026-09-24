package com.ionicframework.cordova.webview;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
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
  private boolean forceRepaint;
  private boolean enableRenderDiagnostics;

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

    boolean forceSoftwareRendering = preferences.getBoolean(PREF_FORCE_SOFTWARE_RENDERING, false);
    if (forceSoftwareRendering) {
      webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
      Log.w(TAG, "IonicWebViewForceSoftwareRendering enabled: forcing WebView software rendering. This may reduce performance.");
    }

    final WebSettings settings = webView.getSettings();
    int mode = preferences.getInteger("MixedContentMode", 0);
    settings.setMixedContentMode(mode);

    logRenderState("init", webView.getUrl());
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
    String paramsDescription = params == null ? "null" : params.getClass().getName() + "(width=" + params.width + ",height=" + params.height + ")";

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
      view.loadUrl("javascript:(function() { " +
              "window.WEBVIEW_SERVER_URL = '" + CDV_LOCAL_SERVER + "';" +
              "})()");
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
