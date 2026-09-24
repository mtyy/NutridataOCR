package ee.mty.nutidataocr

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ee.mty.nutidataocr.ui.theme.NutidataOCRTheme

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var canGoBack by mutableStateOf(false)
    private var loadingProgress by mutableIntStateOf(0)
    private var pageError by mutableStateOf<Int?>(null)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        webView = createWebView()
        val restoredHistory = savedInstanceState?.getBundle("web_view")?.let(webView::restoreState)
        if (restoredHistory == null) webView.loadUrl(NUTRIDATA_URL)
        canGoBack = webView.canGoBack()
        setContent {
            NutidataOCRTheme {
                BackHandler(enabled = canGoBack) { webView.goBack() }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("NutriData", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                IconButton(onClick = { webView.goBack() }, enabled = canGoBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.web_back))
                                }
                            },
                            actions = {
                                IconButton(onClick = { webView.reload() }) {
                                    Icon(Icons.Default.Refresh, stringResource(R.string.web_reload))
                                }
                                TextButton(onClick = {
                                    startActivity(Intent(this@MainActivity, OcrActivity::class.java))
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Text(stringResource(R.string.scan_food))
                                }
                            },
                        )
                    },
                ) { innerPadding ->
                    Column(Modifier.fillMaxSize().padding(innerPadding)) {
                        if (loadingProgress < 100 && pageError == null) {
                            LinearProgressIndicator(
                                progress = { loadingProgress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Box(Modifier.fillMaxSize()) {
                            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                            pageError?.let { error ->
                                androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                    ) {
                                        Text(stringResource(error))
                                        Button(onClick = { webView.reload() }) {
                                            Text(stringResource(R.string.web_retry))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadingProgress = newProgress
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "tap.nutridata.ee" &&
                    (uri.port == -1 || uri.port == 443)
                ) return false
                if (request.isForMainFrame && uri.scheme in setOf("https", "http", "mailto", "tel")) {
                    openExternalLink(uri)
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageError = null
                loadingProgress = 0
                canGoBack = view.canGoBack()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                canGoBack = view.canGoBack()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                canGoBack = view.canGoBack()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) pageError = R.string.web_load_failed
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) pageError = R.string.web_load_failed
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                pageError = R.string.web_secure_connection_failed
            }
        }
    }

    private fun openExternalLink(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.web_no_link_handler, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val browserState = Bundle()
        webView.saveState(browserState)
        outState.putBundle("web_view", browserState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }
}

private const val NUTRIDATA_URL = "https://tap.nutridata.ee/et/"
