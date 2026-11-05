package online.timefiles.vantaloom.phone.a11y

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import online.timefiles.vantaloom.phone.core.driver.Screenshot
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 截图的像素处理：缩放、JPEG 编码、亮度方差、序号徽标标注。全部是纯 Bitmap 运算，不碰无障碍 API，
 * 便于将来在仪器测试里单测，也便于后台虚拟屏驱动（ImageReader 出的 Bitmap）复用。
 */
object ImageCodec {

    /** 缩放结果：scale = 输出像素 / 屏幕像素。 */
    data class Scaled(val bitmap: Bitmap, val scale: Double)

    /**
     * 把长边缩到 ≤ [longEdge]。不放大（scale ≤ 1）。返回的 Bitmap 一定是 ARGB_8888 的软件位图
     * （HARDWARE 位图不能 getPixels / 不能在其上 Canvas 绘制）。
     */
    fun scaleToLongEdge(src: Bitmap, longEdge: Int): Scaled {
        val w = src.width
        val h = src.height
        val edge = max(w, h)
        val scale = if (edge > longEdge && longEdge > 0) longEdge.toDouble() / edge else 1.0
        val tw = max(1, (w * scale).roundToInt())
        val th = max(1, (h * scale).roundToInt())
        val soft = if (src.config == Bitmap.Config.ARGB_8888 && src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("无法把截图拷贝为 ARGB_8888")
        val out = if (tw == w && th == h) soft else Bitmap.createScaledBitmap(soft, tw, th, true)
        if (out !== soft && soft !== src) soft.recycle()
        // 实际缩放比按整数像素重算，保证 tap_xy 映射与图片尺寸严格一致。
        val realScale = if (w > 0) tw.toDouble() / w else scale
        return Scaled(out, realScale)
    }

    /** JPEG 编码。quality 夹到 1..100。 */
    fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream(64 * 1024)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), bos)
        return bos.toByteArray()
    }

    /**
     * 亮度方差：先下采样到 64×64 再算 Rec.601 亮度的方差。几乎全黑（FLAG_SECURE 截出来的黑屏、
     * 熄屏）方差 < 2，core 据此判 SECURE_WINDOW（协议 §10）。下采样让计算是常数时间。
     */
    fun lumaVariance(bitmap: Bitmap): Double {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val px = IntArray(64 * 64)
        small.getPixels(px, 0, 64, 0, 0, 64, 64)
        if (small !== bitmap) small.recycle()
        var sum = 0.0
        var sumSq = 0.0
        for (c in px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = 0.299 * r + 0.587 * g + 0.114 * b
            sum += y
            sumSq += y * y
        }
        val n = px.size.toDouble()
        val mean = sum / n
        return (sumSq / n - mean * mean).coerceAtLeast(0.0)
    }

    /**
     * 在缩放后的位图副本上画序号徽标并重新编码。marks 是**屏幕像素**框，按 [scale] 换算。
     * 徽标：bounds 左上角一个半透明深色圆角矩形 + 白字；highlight=true 的元素再画 2px 橙色描边框。
     * 普通元素也画一道细描边（协议 §10「2 px 描边」），帮助模型把序号对到具体控件。
     */
    fun annotate(base: Bitmap, scale: Double, marks: List<Screenshot.Mark>, quality: Int): ByteArray {
        val bmp = base.copy(Bitmap.Config.ARGB_8888, true) ?: return encodeJpeg(base, quality)
        try {
            val canvas = Canvas(bmp)
            val density = (max(bmp.width, bmp.height) / 1280f).coerceIn(0.5f, 2f)
            val textSize = 22f * density
            val pad = 4f * density
            val radius = 6f * density

            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.argb(180, 30, 144, 255)
            }
            val highlightOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * density
                color = Color.rgb(255, 140, 0)
            }
            val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(200, 20, 20, 28)
            }
            val badgeBgHi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(230, 230, 110, 0)
            }
            val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.WHITE
            }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.textSize = textSize
                typeface = Typeface.DEFAULT_BOLD
            }
            val fm = text.fontMetrics

            for (m in marks) {
                val l = (m.left * scale).toFloat()
                val t = (m.top * scale).toFloat()
                val r = (m.right * scale).toFloat()
                val b = (m.bottom * scale).toFloat()
                if (r <= l || b <= t) continue
                canvas.drawRect(l, t, r, b, if (m.highlight) highlightOutline else outline)

                val tw = text.measureText(m.label)
                val bw = tw + pad * 2
                val bh = (fm.descent - fm.ascent) + pad
                // 徽标贴在框内左上角；框太靠右/下时夹回画布内，避免序号被裁掉。
                val bx = l.coerceIn(0f, max(0f, bmp.width - bw))
                val by = t.coerceIn(0f, max(0f, bmp.height - bh))
                val rect = RectF(bx, by, bx + bw, by + bh)
                canvas.drawRoundRect(rect, radius, radius, if (m.highlight) badgeBgHi else badgeBg)
                canvas.drawRoundRect(rect, radius, radius, badgeStroke)
                canvas.drawText(m.label, bx + pad, by + pad / 2 - fm.ascent, text)
            }
            return encodeJpeg(bmp, quality)
        } finally {
            bmp.recycle()
        }
    }
}
