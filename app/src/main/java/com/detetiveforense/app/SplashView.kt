package com.detetiveforense.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.random.Random

/** Tela de abertura animada com o logo do Detetive Forense. */
@SuppressLint("ViewConstructor")
class SplashView(context: Context) : FrameLayout(context) {

    companion object {
        // Logo original: 146 x 85 unidades. Quadrados: x, y, largura, altura, cor
        private const val CW = 146f
        private const val CH = 85f
        private val SQUARES = listOf(
            Sq(98, 18, 19, 19, "#0090FF"), Sq(96, 61, 15, 15, "#0090FF"),
            Sq(117, 3, 15, 15, "#FF4237"), Sq(122, 22, 9, 10, "#FD49FF"),
            Sq(112, 48, 10, 9, "#FF4237"), Sq(117, 67, 10, 9, "#FF4237"),
            Sq(136, 16, 10, 9, "#0090FF"), Sq(122, 42, 6, 6, "#0090FF"),
            Sq(111, 76, 6, 6, "#FD49FF"), Sq(138, 3, 6, 6, "#0090FF"),
            Sq(137, 35, 6, 6, "#FF4237"), Sq(105, 4, 6, 5, "#FD49FF")
        )
        private const val MIN_SHOW_MS = 2600L
    }

    private data class Sq(val x: Int, val y: Int, val w: Int, val h: Int, val color: String)

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val startedAt = SystemClock.uptimeMillis()
    private val logo = FrameLayout(context)
    private val glow = View(context)
    private val body = ImageView(context)
    private val iris = ImageView(context)
    private val scan = View(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val barTrack = FrameLayout(context)
    private val barFill = View(context)
    private val squares = mutableListOf<View>()
    private val loops = mutableListOf<Animator>()
    private var dismissed = false

    init {
        setBackgroundColor(Color.parseColor("#0B0F17"))
        isClickable = true // bloqueia toques no site por baixo

        val logoW = dp(220f)
        val logoH = logoW * CH / CW
        val unit = logoW / CW

        // Brilho azul atrás do olho
        glow.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(Color.parseColor("#550090FF"), Color.TRANSPARENT)
            gradientRadius = dp(170f)
        }
        glow.alpha = 0f
        addView(glow, LayoutParams(dp(360f).toInt(), dp(360f).toInt(), Gravity.CENTER).apply {
            bottomMargin = dp(70f).toInt()
        })

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        body.setImageResource(R.drawable.splash_eye_body)
        body.scaleType = ImageView.ScaleType.FIT_XY
        iris.setImageResource(R.drawable.splash_eye_iris)
        iris.scaleType = ImageView.ScaleType.FIT_XY
        logo.addView(body, LayoutParams(-1, -1))
        logo.addView(iris, LayoutParams(-1, -1))
        // ponto de pivô da íris = centro do círculo
        logo.post {
            iris.pivotX = 80f * unit + 0f
            iris.pivotY = 42.5f * unit
        }

        for (s in SQUARES) {
            val v = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(s.color))
                    cornerRadius = unit * 0.6f
                }
                alpha = 0f
            }
            logo.addView(v, LayoutParams((s.w * unit).toInt(), (s.h * unit).toInt()).apply {
                leftMargin = (s.x * unit).toInt()
                topMargin = (s.y * unit).toInt()
            })
            squares += v
        }

        // linha de "scanner" que passa pelo olho
        scan.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#CCFFFFFF"), Color.TRANSPARENT)
        )
        scan.alpha = 0f
        logo.addView(scan, LayoutParams(-1, dp(2f).toInt()))
        logo.clipChildren = false

        column.addView(logo, LinearLayout.LayoutParams(logoW.toInt(), logoH.toInt()))

        title.apply {
            text = "DETETIVE FORENSE"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.6f
            alpha = 0f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        column.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28f).toInt() })

        subtitle.apply {
            text = "Sistema exclusivo para investigadores profissionais"
            setTextColor(Color.parseColor("#8A94A6"))
            textSize = 12.5f
            alpha = 0f
            gravity = Gravity.CENTER
        }
        column.addView(subtitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f).toInt() })

        barTrack.background = GradientDrawable().apply {
            setColor(Color.parseColor("#1F2937"))
            cornerRadius = dp(2f)
        }
        barTrack.clipChildren = true
        barTrack.alpha = 0f
        barFill.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#0090FF"), Color.parseColor("#FD49FF"), Color.parseColor("#FF4237"))
        ).apply { cornerRadius = dp(2f) }
        barTrack.addView(barFill, LayoutParams(dp(54f).toInt(), -1))
        column.addView(barTrack, LinearLayout.LayoutParams(dp(150f).toInt(), dp(3f).toInt()).apply {
            topMargin = dp(36f).toInt()
        })

        addView(column, LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun start() {
        val dec = DecelerateInterpolator(2f)

        // 1) brilho
        glow.scaleX = 0.4f; glow.scaleY = 0.4f
        glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(dec).withEndAction {
            val pulse = ObjectAnimator.ofFloat(glow, View.ALPHA, 1f, 0.55f).apply {
                duration = 1400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            }
            pulse.start(); loops += pulse
        }.start()

        // 2) corpo do olho
        body.alpha = 0f
        body.translationX = -dp(50f)
        body.scaleX = 0.85f; body.scaleY = 0.85f
        body.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(120).setDuration(650).setInterpolator(dec).start()

        // 3) íris "abrindo"
        iris.alpha = 0f
        iris.scaleX = 0f; iris.scaleY = 0f
        iris.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(480).setDuration(550).setInterpolator(OvershootInterpolator(2.2f)).start()

        // 4) pixels se montando (do mais perto da íris para o mais longe)
        val order = squares.indices.sortedBy { i ->
            val s = SQUARES[i]; hypot((s.x - 80f).toDouble(), (s.y - 42f).toDouble())
        }
        order.forEachIndexed { n, i ->
            val v = squares[i]
            v.translationX = dp(Random.nextInt(40, 110).toFloat())
            v.translationY = dp(Random.nextInt(-70, 70).toFloat())
            v.rotation = Random.nextInt(-120, 120).toFloat()
            v.scaleX = 0.2f; v.scaleY = 0.2f
            v.animate().alpha(1f).translationX(0f).translationY(0f).rotation(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(750L + n * 55L).setDuration(480).setInterpolator(OvershootInterpolator(1.4f)).start()
        }

        // 5) scanner passando pelo olho
        postDelayed({
            if (dismissed) return@postDelayed
            scan.alpha = 1f
            val h = logo.height.toFloat()
            ObjectAnimator.ofFloat(scan, View.TRANSLATION_Y, 0f, h).apply {
                duration = 700; interpolator = AccelerateInterpolator(0.8f)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        scan.animate().alpha(0f).setDuration(150).start()
                    }
                })
            }.start()
        }, 1400)

        // 6) título com espaçamento das letras fechando
        title.translationY = dp(14f)
        postDelayed({
            if (dismissed) return@postDelayed
            title.animate().alpha(1f).translationY(0f).setDuration(600).setInterpolator(dec).start()
            ValueAnimator.ofFloat(0.6f, 0.14f).apply {
                duration = 900; interpolator = dec
                addUpdateListener { title.letterSpacing = it.animatedValue as Float }
            }.start()
        }, 1350)

        subtitle.animate().alpha(1f).setStartDelay(1800).setDuration(500).start()

        // 7) barra de carregamento
        barTrack.animate().alpha(1f).setStartDelay(2000).setDuration(400).withEndAction {
            val run = ObjectAnimator.ofFloat(barFill, View.TRANSLATION_X, -dp(54f), dp(150f)).apply {
                duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            }
            run.start(); loops += run
            // olhar da íris (leve)
            val look = ObjectAnimator.ofFloat(iris, View.TRANSLATION_X, 0f, -dp(3f), 0f, dp(2f), 0f).apply {
                duration = 2400; repeatCount = ValueAnimator.INFINITE
            }
            look.start(); loops += look
        }.start()
    }

    /** Fecha a abertura respeitando o tempo mínimo da animação. */
    fun dismiss() {
        if (dismissed) return
        val wait = MIN_SHOW_MS - (SystemClock.uptimeMillis() - startedAt)
        if (wait > 0) {
            postDelayed({ dismiss() }, wait)
            return
        }
        dismissed = true
        logo.animate().scaleX(1.25f).scaleY(1.25f).setDuration(450).setInterpolator(AccelerateInterpolator()).start()
        animate().alpha(0f).setStartDelay(120).setDuration(400).withEndAction {
            loops.forEach { it.cancel() }
            (parent as? ViewGroup)?.removeView(this)
        }.start()
    }
}
