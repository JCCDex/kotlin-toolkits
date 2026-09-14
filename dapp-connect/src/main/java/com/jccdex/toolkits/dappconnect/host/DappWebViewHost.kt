package com.jccdex.toolkits.dappconnect.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jccdex.toolkits.dappconnect.DAppConnectSdk
import com.jccdex.toolkits.dappconnect.WebAppInterface
import com.jccdex.toolkits.dappconnect.middleware.IEthMiddleware
import com.jccdex.toolkits.dappconnect.middleware.ISwtcMiddleware
import com.jccdex.toolkits.dappconnect.provider.AccountProvider
import com.jccdex.toolkits.dappconnect.provider.CachingSecretProvider
import com.jccdex.toolkits.dappconnect.provider.ChainProvider
import com.jccdex.toolkits.dappconnect.provider.NftProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** DApp 应用内浏览器宿主状态(与两端共享页面 `DAppBrowserScreen` 的状态模型同形)。 */
data class DappWebViewState(
    val title: String = "",
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val loaded: Boolean = false,
    val failed: Boolean = false
)

/**
 * DApp WebView 宿主配置。
 *
 * provider 图与页面脚本由调用方(JDID)提供:宿主只负责 WebView/客户端/注入顺序与状态推送,
 * 业务编排(授权持久化、地址推送、保险库取钥、文件选择、blob 落盘)经 [onPageStarted]/
 * [onPageFinished]/[DappWebViewHostConfig.preProviderScripts]/
 * [DappWebViewHostConfig.postProviderScripts] 交给 App 侧。
 *
 * @param preProviderScripts provider 脚本之前注入的脚本(blob/剪贴板等 patch)
 * @param postProviderScripts provider 脚本之后注入的脚本(依赖 provider 的地址去重等 patch)
 * @param isInternalPreviewUrl 内部预览 URL(blob:)判定:命中时跳过 origin 同步与脚本注入
 * @param onPageStarted 页面开始回调(在 origin 同步之前)
 * @param onPageFinished 页面完成回调(在脚本注入之后)
 */
class DappWebViewHostConfig(
    val ethMiddleware: IEthMiddleware,
    val swtcMiddleware: ISwtcMiddleware,
    val accountProvider: AccountProvider,
    val secretProvider: CachingSecretProvider,
    val nftProvider: NftProvider,
    val providerJs: String,
    val preProviderScripts: List<String> = emptyList(),
    val postProviderScripts: List<String> = emptyList(),
    val isInternalPreviewUrl: (String?) -> Boolean = { false },
    val onPageStarted: ((url: String) -> Unit)? = null,
    val onPageFinished: ((url: String) -> Unit)? = null,
    /**
     * WebView 文件上传请求(`<input type=file>`):调用方负责拉起选择器,
     * 拿到结果后调 [DappWebViewHost.deliverFileChooserResult] 回填。返回 true = 已接管。
     */
    val onShowFileChooser: ((params: WebChromeClient.FileChooserParams?) -> Boolean)? = null,
    /**
     * 链信息提供者(DApp `wallet_switchEthereumChain`/`eth_chainId` 等);null = 不设置,
     * 由中间件默认行为处理。
     */
    val chainProvider: ChainProvider? = null,
    /**
     * H-DID1:`did_issueCredential` 的宿主确认回调(展示确认 UI 后返回是否放行)。
     * null = 不设置 → SDK fail-closed 拒绝签发(与 `WebAppInterface.setDidCredentialConfirm` 一致)。
     */
    val didCredentialConfirm: (suspend (String) -> Boolean)? = null
)

/**
 * Android DApp WebView 宿主(自 JDID `ExploreDAppScreen` 下沉的平台管线):
 * WebView 设置、`_tw_` provider 接口与响应通道、页面脚本注入、标题/进度/后退状态推送。
 *
 * 语义与 JDID 原实现逐条一致(同一 settings、同一注入顺序、同一 SSL 拒绝策略、
 * 不接管网络错误页以免改变既有行为)。
 */
@SuppressLint("SetJavaScriptEnabled")
class DappWebViewHost(
    context: Context,
    private val config: DappWebViewHostConfig
) {
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val _state = MutableStateFlow(DappWebViewState())

    /** 标题/进度/可否后退/就绪(共享页面消费)。 */
    val state: StateFlow<DappWebViewState> = _state.asStateFlow()

    /** 承载页面的 WebView(Compose `AndroidView` 渲染同一实例)。 */
    val webView: WebView =
        WebView(context).apply {
            layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                javaScriptCanOpenWindowsAutomatically = true
                safeBrowsingEnabled = true
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }

    private val webAppInterface: WebAppInterface =
        DAppConnectSdk
            .createWebAppInterface(
                webView,
                config.ethMiddleware,
                config.swtcMiddleware,
                config.accountProvider,
                config.secretProvider,
                config.nftProvider
            ).also { appInterface ->
                config.chainProvider?.let { appInterface.setChainProvider(it) }
                // H-DID1:未提供确认回调时保持 SDK fail-closed 语义(拒绝签发)。
                appInterface.setDidCredentialConfirm(config.didCredentialConfirm)
                webView.addJavascriptInterface(appInterface, JS_INTERFACE_NAME)
            }

    init {
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    update(progress = 0)
                    config.onPageStarted?.invoke(url.orEmpty())
                    if (config.isInternalPreviewUrl(url)) return
                    syncOrigin(url)
                    config.secretProvider.clearCache()
                    injectPageScripts(view)
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    super.onPageFinished(view, url)
                    update(progress = 100)
                    if (config.isInternalPreviewUrl(url)) {
                        view?.title?.takeIf { it.isNotBlank() }?.let { update(title = it) }
                        return
                    }
                    syncOrigin(url)
                    injectPageScripts(view)
                    view?.title?.takeIf { it.isNotBlank() }?.let { update(title = it) }
                    update(canGoBack = view?.canGoBack() == true)
                    config.onPageFinished?.invoke(url.orEmpty())
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.cancel()
                }
            }
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = null
                    if (filePathCallback == null) return false
                    val handler = config.onShowFileChooser
                    if (handler == null || !handler(fileChooserParams)) {
                        filePathCallback.onReceiveValue(null)
                        return false
                    }
                    pendingFileCallback = filePathCallback
                    return true
                }

                override fun onReceivedTitle(
                    view: WebView?,
                    title: String?
                ) {
                    super.onReceivedTitle(view, title)
                    title?.takeIf { it.isNotBlank() }?.let { update(title = it) }
                }

                override fun onProgressChanged(
                    view: WebView?,
                    newProgress: Int
                ) {
                    super.onProgressChanged(view, newProgress)
                    update(progress = newProgress.coerceIn(0, 100))
                    update(canGoBack = view?.canGoBack() == true)
                }
            }
    }

    /** 加载 URL(首次进入);同时把桥 origin 对齐到该地址。 */
    fun load(url: String) {
        syncOrigin(url)
        webView.loadUrl(url)
        update(loaded = true, failed = false)
    }

    /**
     * 刷新 WebMessagePort 响应通道(连接授权后调用)。
     *
     * 首次 `requestAccounts` 的批准发生在 port 就绪之前时,响应会回不到 JS
     * (DApp 报「连接钱包失败」,再点一次才成功);批准后刷新可消除该竞态。
     * 内部切主线程,可在任意线程调用。
     */
    fun refreshResponseChannel() {
        // 必须同步 install:批准后立即发送的响应要落到**新** port 上。
        // (NativeResponseChannel.install 内部 runOnMain 处理线程,主线程调用为同步。)
        webAppInterface.installResponseChannel()
    }

    /** 在页面上下文执行脚本(地址推送/守卫恢复等)。 */
    fun evaluate(script: String) {
        webView.evaluateJavascript(script, null)
    }

    /** 回填文件选择结果(null/空 = 取消);与 Android `FileChooserParams` 交付语义一致。 */
    fun deliverFileChooserResult(uris: Array<Uri>?) {
        val callback = pendingFileCallback
        pendingFileCallback = null
        callback?.onReceiveValue(if (uris.isNullOrEmpty()) null else uris)
    }

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun reload() {
        webView.reload()
    }

    /** 释放 WebView(页面离开)。 */
    fun destroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        runCatching { webView.removeJavascriptInterface(JS_INTERFACE_NAME) }
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        update(canGoBack = false)
    }

    private fun syncOrigin(url: String?) {
        url?.takeIf { it.isNotBlank() }?.let { webAppInterface.setOrigin(it) }
    }

    private fun injectPageScripts(view: WebView?) {
        view ?: return
        config.preProviderScripts.forEach { view.evaluateJavascript(it, null) }
        view.evaluateJavascript(config.providerJs) { webAppInterface.installResponseChannel() }
        config.postProviderScripts.forEach { view.evaluateJavascript(it, null) }
    }

    private fun update(
        title: String? = null,
        progress: Int? = null,
        canGoBack: Boolean? = null,
        loaded: Boolean? = null,
        failed: Boolean? = null
    ) {
        val current = _state.value
        _state.value =
            current.copy(
                title = title ?: current.title,
                progress = progress ?: current.progress,
                canGoBack = canGoBack ?: current.canGoBack,
                loaded = loaded ?: current.loaded,
                failed = failed ?: current.failed
            )
    }

    companion object {
        /** provider JS 适配脚本注册的 window 对象名(与 `ccdao-eip1193-provider` 一致)。 */
        const val JS_INTERFACE_NAME: String = "_tw_"
    }
}
