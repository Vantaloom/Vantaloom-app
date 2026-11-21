package online.timefiles.vantaloom.phone.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import online.timefiles.vantaloom.phone.core.model.Rect
import online.timefiles.vantaloom.phone.core.ports.ActivityPhase

/**
 * 浮层用到的小视图与样式工具。全部代码构建（不用 xml 布局）：浮层并入 Vantaloom-app 时不必合并资源，
 * 也不依赖宿主主题——无障碍服务的 Context 主题在各 ROM 上不一致，显式设色最稳。
 */
internal object OverlayStyle {
    const val CARD_BG = 0xF21E1E24.toInt()
    const val CARD_STROKE = 0x33FFFFFF
    const val TEXT_PRIMARY = Color.WHITE
    const val TEXT_SECONDARY = 0xFFB8B8C4.toInt()
    const val ACCENT = 0xFFFF8C00.toInt()
    const val DANGER = 0xFFE5484D.toInt()
    const val NEUTRAL = 0xFF3A3A46.toInt()
    const val POSITIVE = 0xFF2E9E5B.toInt()

    fun dp(context: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    /** 阶段 → 小球颜色。颜色是「一眼看懂 agent 在干嘛」的唯一信号，所以每个阶段都要明显不同。 */
    fun phaseColor(phase: ActivityPhase): Int = when (phase) {
        ActivityPhase.OBSERVING -> 0xFF3B82F6.toInt() // 蓝：在看
        ActivityPhase.ACTING -> 0xFF22C55E.toInt() // 绿：在动手
        ActivityPhase.WAITING_CONFIRM -> ACCENT // 橙：等你确认
        ActivityPhase.WAITING_USER -> 0xFFA855F7.toInt() // 紫：等你接手
        ActivityPhase.PAUSED -> 0xFF9CA3AF.toInt() // 灰：已暂停
        ActivityPhase.IDLE -> 0xFF14B8A6.toInt() // 青：空闲
    }

    fun phaseLabel(phase: ActivityPhase): String = when (phase) {
        ActivityPhase.OBSERVING -> "观察中"
        ActivityPhase.ACTING -> "操作中"
        ActivityPhase.WAITING_CONFIRM -> "等待确认"
        ActivityPhase.WAITING_USER -> "等待你接手"
        ActivityPhase.PAUSED -> "已暂停"
        ActivityPhase.IDLE -> "空闲"
    }

    fun roundRect(context: Context, color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(dp(context, 1f), strokeColor)
        }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(context, 16f)
        setPadding(p, p, p, p)
        background = roundRect(context, CARD_BG, 16f, CARD_STROKE)
        elevation = dp(context, 8f).toFloat()
    }

    fun text(context: Context, value: CharSequence, sizeSp: Float, color: Int = TEXT_PRIMARY, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) paint.isFakeBoldText = true
            setLineSpacing(0f, 1.15f)
        }

    /** 给按钮（普通 Button 或 GuardedButton）统一上色与尺寸。 */
    fun styleButton(b: Button, color: Int) {
        val c = b.context
        b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        b.setAllCaps(false) // isAllCaps 的 getter 是 API 28，直接调 setter
        b.background = roundRect(c, color, 10f)
        b.minHeight = dp(c, 44f) // 触控目标 ≥ 44dp（可访问性）
        b.minimumHeight = dp(c, 44f)
        val h = dp(c, 14f)
        b.setPadding(h, 0, h, 0)
        b.gravity = Gravity.CENTER
    }

    fun lp(context: Context, topMarginDp: Float = 0f, width: Int = LinearLayout.LayoutParams.MATCH_PARENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, topMarginDp)
        }
}

/**
 * 状态小球：一个圆，颜色表示阶段；暂停时画两道竖线；被「让开」时半透明。
 * 触摸逻辑（拖动/吸附/点按/长按停止）在 ControlOverlay 里挂 OnTouchListener，这里只负责画。
 */
@SuppressLint("ViewConstructor")
internal class BallView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = OverlayStyle.dp(context, 2f).toFloat()
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = OverlayStyle.DANGER
        strokeWidth = OverlayStyle.dp(context, 3f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()

    var phase: ActivityPhase = ActivityPhase.IDLE
        set(value) {
            field = value
            contentDescription = "Vantaloom 手机操控：" + OverlayStyle.phaseLabel(value)
            invalidate()
        }

    /** 长按停止的进度 0..1（给用户「再按一会儿就停」的反馈）。 */
    var stopProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    init {
        phase = ActivityPhase.IDLE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ring.strokeWidth * 2
        fill.color = OverlayStyle.phaseColor(phase)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        if (phase == ActivityPhase.PAUSED) {
            val bw = r * 0.22f
            val bh = r * 0.9f
            canvas.drawRect(cx - bw * 2, cy - bh / 2, cx - bw, cy + bh / 2, glyph)
            canvas.drawRect(cx + bw, cy - bh / 2, cx + bw * 2, cy + bh / 2, glyph)
        } else {
            canvas.drawCircle(cx, cy, r * 0.28f, glyph)
        }
        if (stopProgress > 0f) {
            val inset = progress.strokeWidth
            arcRect.set(inset, inset, width - inset, height - inset)
            canvas.drawArc(arcRect, -90f, 360f * stopProgress, false, progress)
        }
    }
}

/**
 * 全屏透明、不可触摸的高亮层：在确认卡对应的目标元素上画橙色框，让用户知道「要点的是哪个」。
 * 坐标是屏幕像素；绘制时减去本视图在屏幕上的偏移（状态栏/刘海可能让窗口原点不在 0,0）。
 */
@SuppressLint("ViewConstructor")
internal class HighlightView(context: Context) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = OverlayStyle.ACCENT
        strokeWidth = OverlayStyle.dp(context, 3f).toFloat()
    }
    private val tint = Paint().apply { color = 0x26FF8C00 }
    private val loc = IntArray(2)
    private val rect = RectF()

    var target: Rect? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = target ?: return
        getLocationOnScreen(loc)
        val pad = stroke.strokeWidth
        rect.set(t.left - loc[0] - pad, t.top - loc[1] - pad, t.right - loc[0] + pad, t.bottom - loc[1] + pad)
        val radius = OverlayStyle.dp(context, 6f).toFloat()
        canvas.drawRoundRect(rect, radius, radius, tint)
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }
}
