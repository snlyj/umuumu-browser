package com.yuka.cutebrowser.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.view.View
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.yuka.cutebrowser.data.AppearancePrefs

/** 当前外观设置（壁纸、吉祥物、进度条样式…）。没有 provide 时是默认值，和 1.6 一样 */
val LocalAppearance = staticCompositionLocalOf { AppearancePrefs() }

/**
 * 是否要减少动画：应用内「减少动画」打开，或者系统里把动画关了（开发者选项 / 无障碍「移除动画」，
 * ANIMATOR_DURATION_SCALE = 0）。为 true 时：没有无限循环动画、没有弹簧回弹、没有飘爱心，状态变化直接到位。
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** 点按反馈（震动 / 轻轻的"嗒"）。默认两个都关。 */
class CuteFeedback(private val view: View?, private val haptics: Boolean, private val sound: Boolean) {
    val active: Boolean get() = (haptics || sound) && view != null

    fun tap() {
        val v = view ?: return
        if (haptics) runCatching {
            // CONTEXT_CLICK 是很轻的一下（API 23+）；minSdk 24，所以一定存在
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        // 系统自带的点击音：音量跟随系统「触摸提示音」，不额外打包音频文件
        if (sound) runCatching { v.playSoundEffect(SoundEffectConstants.CLICK) }
    }
}

val LocalFeedback = staticCompositionLocalOf<CuteFeedback?> { null }

/** 纯函数，方便测试：应用内开关或系统动画比例为 0 时都要减少动画 */
fun isMotionReduced(inAppToggle: Boolean, animatorDurationScale: Float): Boolean =
    inAppToggle || animatorDurationScale == 0f

private fun animatorScale(ctx: Context): Float =
    runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

/** 读系统「动画时长缩放」，并在用户改了它之后立刻更新（不用重启 App） */
@Composable
fun rememberAnimatorScale(): Float {
    val ctx = LocalContext.current
    var scale by remember { mutableStateOf(animatorScale(ctx)) }
    DisposableEffect(ctx) {
        val resolver = ctx.contentResolver
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = animatorScale(ctx) }
        }
        runCatching {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, obs)
        }
        onDispose { runCatching { resolver.unregisterContentObserver(obs) } }
    }
    return scale
}

/** 把外观设置、减少动画、点按反馈一起提供给下面的界面 */
@Composable
fun ProvideAppearance(prefs: AppearancePrefs, content: @Composable () -> Unit) {
    val reduce = isMotionReduced(prefs.reduceMotion, rememberAnimatorScale())
    val view = LocalView.current
    val feedback = remember(view, prefs.haptics, prefs.sound) { CuteFeedback(view, prefs.haptics, prefs.sound) }
    CompositionLocalProvider(
        LocalAppearance provides prefs,
        LocalReduceMotion provides reduce,
        LocalFeedback provides feedback,
        content = content,
    )
}

/** 减少动画时用 snap（直接到位），否则用传入的动画 */
@Composable
fun <T> motionSpec(spec: AnimationSpec<T>): AnimationSpec<T> = if (LocalReduceMotion.current) snap() else spec
