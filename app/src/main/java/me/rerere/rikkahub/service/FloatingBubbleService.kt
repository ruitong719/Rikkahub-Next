package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.BitmapShader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxAdsorbDirection
import com.petterp.floatingx.assist.FxScopeType
import com.petterp.floatingx.listener.IFxTouchListener
import com.petterp.floatingx.listener.control.IFxAppControl
import com.petterp.floatingx.view.IFxInternalHelper
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.svg.SvgDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.FLOATING_BUBBLE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.IconSource
import me.rerere.rikkahub.ui.floating.FloatingExpandWindow
import me.rerere.rikkahub.utils.svgToDataUri
import org.koin.android.ext.android.inject
import java.io.File

private const val TAG = "FloatingBubbleService"

/**
 * 悬浮球前台服务: 在系统层显示一个可拖动、可半隐藏的悬浮小球，
 * 点击小球可回到 RikkaHub。颜色/大小/开关由偏好设置实时驱动。
 *
 * 注意: 悬浮球运行在 Service 进程中, 没有 Activity 的 LifecycleOwner,
 * 因此不能使用 ComposeView (setContent 会因缺少 ViewTreeLifecycleOwner 崩溃),
 * 改用普通 View + GradientDrawable 绘制纯色圆球。
 */
class FloatingBubbleService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.FLOATING_BUBBLE_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.FLOATING_BUBBLE_STOP"
        const val ACTION_RESUME = "me.rerere.rikkahub.action.FLOATING_BUBBLE_RESUME"
        const val FLOATING_X_TAG = "floating_bubble"
        const val NOTIFICATION_ID = 3002

        // 供 RouteActivity 判断是否需要发送恢复动作（进程内静态状态）
        @Volatile
        var serviceRunning: Boolean = false
            private set

        @Volatile
        var tempHidden: Boolean = false
            private set

        private const val SIZE_MIN_DP = 32
        private const val SIZE_MAX_DP = 80
        private const val HALF_HIDE_FACTOR = 0.5f
        private const val DOUBLE_CLICK_INTERVAL_MS = 350L
        private const val LONG_PRESS_TIMEOUT_MS = 500L
        private const val LONG_PRESS_CLICK_GUARD_MS = 700L
        private const val BUBBLE_OPACITY_MIN_PERCENT = 20
        private const val ICON_TARGET_SIZE_PX = 256
    }

    private val settingsStore: SettingsStore by inject()
    private val floatingActivityHub: FloatingActivityHub by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var settingsJob: Job? = null

    private var control: IFxAppControl? = null
    private var bubbleView: BubbleView? = null
    private var expandWindow: FloatingExpandWindow? = null

    // 悬浮球外观状态
    private var bubbleColor = 0xFF4F8EF7.toInt()
    private var bubbleSizeDp = 48
    private var bubbleBaseAlpha = 1f
    private var lastIconPath: String? = null

    // 新版图标来源（SVG/URL/Emoji），与 lastIconPath 一起构成回退链的变更检测
    private var lastIcon: IconSource? = null

    // 服务内独立的图片加载器：不依赖 Compose 侧单例初始化（服务可能先于 Activity 启动）
    private val iconImageLoader: ImageLoader by lazy {
        ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory(scaleToDensity = true))
                add(OkHttpNetworkFetcherFactory())
            }
            .build()
    }

    // 交互状态
    private var isHalfHidden = false
    private var hasDragged = false
    private var lastClickTime = 0L
    private var longPressFiredAt = 0L

    private val singleClickRunnable = Runnable {
        toggleExpandWindow()
    }

    // 长按（按住不动超时）：直接回 App 主界面；时间戳用于吞掉松手时的补发单击
    private val longPressRunnable = Runnable {
        if (!hasDragged && control != null) {
            longPressFiredAt = SystemClock.elapsedRealtime()
            expandWindow?.hide()
            launchApp()
        }
    }

    /** 用户设置的基础透明度 × 半隐藏系数 */
    private fun applyBubbleAlpha() {
        val effective = bubbleBaseAlpha * if (isHalfHidden) HALF_HIDE_FACTOR else 1f
        bubbleView?.setBubbleAlpha(effective)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_RESUME -> {
                // 主页面再次打开：恢复被"暂停显示"的悬浮球
                if (tempHidden && control != null) {
                    tempHidden = false
                    control?.show()
                }
            }

            else -> {
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "No overlay permission, stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                setupBubble()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceRunning = false
        settingsJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        expandWindow?.hide()
        expandWindow = null
        control?.cancel()
        control = null
        bubbleView = null
        serviceScope.cancel()
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, FLOATING_BUBBLE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_floating_bubble_running))
            .setContentText(getString(R.string.notification_floating_bubble_desc))
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun setupBubble() {
        val existing = control
        if (existing != null) {
            // 暂停显示期间不重新展示，等主页面发 RESUME 动作恢复
            if (!tempHidden) existing.show()
            observeSettings()
            return
        }

        val view = BubbleView(this).apply {
            setBubbleColor(bubbleColor)
            setBubbleAlpha(bubbleBaseAlpha)
            setBubbleSize(dp2px(bubbleSizeDp).toInt())
        }
        bubbleView = view

        control = FloatingX.install {
            setTag(FLOATING_X_TAG)
            setContext(this@FloatingBubbleService)
            setScopeType(FxScopeType.SYSTEM_AUTO)
            setEnableSafeArea(true)
            setLayoutView(view)
        }
        control?.configControl?.apply {
            setEnableEdgeAdsorption(true)
            setEdgeAdsorbDirection(FxAdsorbDirection.LEFT_OR_RIGHT)
            setEdgeOffset(0f)
            setEnableClick(true)
            setEnableAnimation(true)
            setTouchListener(object : IFxTouchListener {
                override fun onDown() {
                    hasDragged = false
                }

                override fun onUp() {
                    if (hasDragged) {
                        mainHandler.postDelayed({ updateHalfHideState() }, 150)
                    }
                }

                override fun onDragIng(event: MotionEvent, x: Float, y: Float) {
                    hasDragged = true
                }

                override fun onTouch(event: MotionEvent, control: IFxInternalHelper?): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN ->
                            mainHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            mainHandler.removeCallbacks(longPressRunnable)

                        else -> Unit
                    }
                    return false
                }

                override fun onInterceptTouchEvent(event: MotionEvent, control: IFxInternalHelper?): Boolean = false
            })
        }
        control?.apply {
            setClickListener { handleClick() }
            if (!tempHidden) show()
        }

        observeSettings()
    }

    private fun observeSettings() {
        if (settingsJob != null) return
        settingsJob = serviceScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                if (!settings.floatingBubbleEnabled) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                bubbleColor = settings.floatingBubbleColor.toInt()
                bubbleSizeDp = settings.floatingBubbleSize.coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)
                bubbleBaseAlpha =
                    settings.floatingBubbleOpacity.coerceIn(BUBBLE_OPACITY_MIN_PERCENT, 100) / 100f
                applyBubbleAlpha()
                bubbleView?.setBubbleColor(bubbleColor)
                bubbleView?.setBubbleSize(dp2px(bubbleSizeDp).toInt())
                val icon = settings.floatingBubbleIcon
                val iconPath = settings.floatingBubbleIconPath
                if (icon != lastIcon || iconPath != lastIconPath) {
                    lastIcon = icon
                    lastIconPath = iconPath
                    serviceScope.launch(Dispatchers.IO) {
                        // 新版来源优先，失败或为空时回退旧的本地 PNG，再回退纯色圆球
                        val bitmap = resolveBubbleIcon(icon)
                            ?: iconPath?.let { loadBubbleIcon(File(it)) }
                        mainHandler.post { bubbleView?.setIconBitmap(bitmap) }
                    }
                }
            }
        }
    }

    private fun handleClick() {
        // 长按刚触发过：吞掉松手时 floatingx 补发的单击
        if (SystemClock.elapsedRealtime() - longPressFiredAt < LONG_PRESS_CLICK_GUARD_MS) return
        if (isHalfHidden) {
            restoreFromHalfHide()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTime < DOUBLE_CLICK_INTERVAL_MS) {
            // 双击：暂停显示悬浮球（服务保活），下次打开 App 主界面时自动恢复
            mainHandler.removeCallbacks(singleClickRunnable)
            lastClickTime = 0L
            pauseBubbleTemporarily()
        } else {
            // 单击：延迟到双击判定窗口之后执行展开/收起
            lastClickTime = now
            mainHandler.removeCallbacks(singleClickRunnable)
            mainHandler.postDelayed(singleClickRunnable, DOUBLE_CLICK_INTERVAL_MS)
        }
    }

    private fun toggleExpandWindow() {
        val window = expandWindow ?: FloatingExpandWindow(this, floatingActivityHub, settingsStore) {
            pauseBubbleTemporarily()
        }.also {
            expandWindow = it
        }
        if (window.isShowing) {
            window.hide()
        } else {
            window.show()
        }
    }

    /** 双击 / 面板按钮：隐藏悬浮球与面板；服务保活，主页面 RESUME 时恢复 */
    private fun pauseBubbleTemporarily() {
        tempHidden = true
        expandWindow?.hide()
        control?.hide()
    }

    private fun launchApp() {
        val intent = Intent(this, RouteActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }.onFailure {
            Log.e(TAG, "launchApp failed", it)
        }
    }

    private fun updateHalfHideState() {
        val c = control ?: return
        val sizePx = dp2px(bubbleSizeDp)
        val screenWidthPx = resources.displayMetrics.widthPixels
        val x = c.getX()
        val nearLeft = x <= sizePx * 0.5f
        val nearRight = x >= screenWidthPx - sizePx * 1.5f
        if (nearLeft || nearRight) {
            if (isHalfHidden) return
            isHalfHidden = true
            applyBubbleAlpha()
            val targetX = if (nearLeft) -sizePx / 2f else screenWidthPx - sizePx / 2f
            c.move(targetX, c.getY(), true)
        } else if (isHalfHidden) {
            isHalfHidden = false
            applyBubbleAlpha()
        }
    }

    private fun restoreFromHalfHide() {
        isHalfHidden = false
        applyBubbleAlpha()
        val c = control ?: return
        val sizePx = dp2px(bubbleSizeDp)
        val screenWidthPx = resources.displayMetrics.widthPixels
        val targetX = if (c.getX() < screenWidthPx / 2f) 0f else screenWidthPx - sizePx
        c.move(targetX, c.getY(), true)
    }

    private fun dp2px(dp: Int): Float = dp * resources.displayMetrics.density

    /** 新版图标来源（SVG 源码 / 图片 URL / Emoji）转方形位图；失败返回 null 走回退链 */
    private suspend fun resolveBubbleIcon(source: IconSource?): Bitmap? {
        if (source == null) return null
        return runCatching {
            when (source) {
                is IconSource.Emoji -> drawEmojiBitmap(source.emoji, ICON_TARGET_SIZE_PX)
                else -> {
                    val data = when (source) {
                        is IconSource.Svg -> svgToDataUri(source.code)
                        is IconSource.Url -> source.url
                    }
                    val request = ImageRequest.Builder(this)
                        .data(data)
                        .size(ICON_TARGET_SIZE_PX, ICON_TARGET_SIZE_PX)
                        .build()
                    val result = iconImageLoader.execute(request)
                    val image = (result as? SuccessResult)?.image ?: return@runCatching null
                    image.toSquareBitmap(ICON_TARGET_SIZE_PX)
                }
            }
        }.getOrNull()
    }

    private fun coil3.Image.toSquareBitmap(sizePx: Int): Bitmap {
        (this as? BitmapImage)?.bitmap?.let { src ->
            // 已是位图：居中裁方
            val side = minOf(src.width, src.height)
            val x = (src.width - side) / 2
            val y = (src.height - side) / 2
            return Bitmap.createBitmap(src, x, y, side, side)
        }
        // SVG 等：按等比缩放居中绘制到方形画布
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val scale = minOf(sizePx / w, sizePx / h)
        canvas.translate((sizePx - w * scale) / 2f, (sizePx - h * scale) / 2f)
        canvas.scale(scale, scale)
        draw(canvas)
        return bmp
    }

    private fun drawEmojiBitmap(emoji: String, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx * 0.62f
            textAlign = Paint.Align.CENTER
        }
        val baselineY = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(emoji, sizePx / 2f, baselineY, paint)
        return bmp
    }

    /** 解码旧版自定义图标 PNG 并居中裁成方形（采样到 ~[ICON_TARGET_SIZE_PX]），失败返回 null */
    private fun loadBubbleIcon(file: File): Bitmap? = runCatching {
        if (!file.isFile) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ICON_TARGET_SIZE_PX &&
            bounds.outHeight / (sample * 2) >= ICON_TARGET_SIZE_PX
        ) {
            sample *= 2
        }
        val source = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@runCatching null
        centerCropSquare(source, ICON_TARGET_SIZE_PX)
    }.getOrNull()

    /** 居中裁方并缩放到 [target] 边长 */
    private fun centerCropSquare(source: Bitmap, target: Int): Bitmap {
        val side = minOf(source.width, source.height)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        val cropped = Bitmap.createBitmap(source, x, y, side, side)
        return if (cropped.width == target && cropped.height == target) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, target, target, true)
        }
    }

    /**
     * 悬浮球视图: 普通 View + GradientDrawable 绘制纯色圆球。
     * 支持运行时更新颜色/透明度/大小/自定义图标（圆形裁剪，四周留颜色描边），
     * 不依赖 Compose 与 LifecycleOwner。
     */
    private class BubbleView(context: Context) : View(context) {

        private val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var iconBitmap: Bitmap? = null
        private var sizePx = 0

        init {
            background = drawable
        }

        fun setBubbleColor(color: Int) {
            drawable.setColor(color)
        }

        fun setBubbleAlpha(alpha: Float) {
            drawable.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            iconPaint.alpha = drawable.alpha
            invalidate()
        }

        fun setIconBitmap(bitmap: Bitmap?) {
            iconBitmap = bitmap
            invalidate()
        }

        fun setBubbleSize(px: Int) {
            if (sizePx == px) return
            sizePx = px
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = if (sizePx > 0) sizePx else suggestedMinimumWidth
            setMeasuredDimension(size, size)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bitmap = iconBitmap ?: return
            val border = resources.displayMetrics.density * 2f
            val drawSize = sizePx - border * 2
            if (drawSize <= 0f) return
            // 圆形绘制图标，四周留出背景色描边；透明度跟随整体 alpha
            val matrix = Matrix()
            val scale = drawSize / bitmap.width
            matrix.setScale(scale, scale)
            matrix.postTranslate(border, border)
            iconPaint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(matrix)
            }
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, drawSize / 2f, iconPaint)
            iconPaint.shader = null
        }
    }
}
