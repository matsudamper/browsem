/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package net.matsudamper.browser

import android.view.View
import android.view.ViewGroup
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type.ime
import androidx.core.view.WindowInsetsCompat.Type.systemBars

/**
 * キーボード表示・非表示と [targetView] のリサイズを同期する。
 * Firefox Android (Fenix) と同様に View の bottomMargin で GeckoView を縮める。
 *
 * Compose の imePadding とは異なり recompose を起こさないため、
 * PopupMenu 内入力との相性が良い。
 */
internal class ImeInsetsSynchronizer private constructor(
    private val targetView: View,
    private val insetsSource: View,
    private val synchronizeViewWithIME: Boolean,
    private val onIMEAnimationStarted: (Boolean, Int) -> Unit,
    private val onIMEAnimationFinished: (Boolean, Int) -> Unit,
) : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE),
    OnApplyWindowInsetsListener {

    init {
        ViewCompat.setWindowInsetsAnimationCallback(insetsSource, this)
        ViewCompat.setOnApplyWindowInsetsListener(insetsSource, this)
    }

    private lateinit var lastWindowInsets: WindowInsetsCompat
    private var areKeyboardInsetsDeferred = false
    private var isKeyboardShowingUp: Boolean = true
    private var keyboardAnimationInProgress = false
    private var keyboardHeight = 0

    override fun onApplyWindowInsets(
        view: View,
        windowInsets: WindowInsetsCompat,
    ): WindowInsetsCompat {
        lastWindowInsets = windowInsets
        isKeyboardShowingUp = windowInsets.isKeyboardShowingUp

        if (!areKeyboardInsetsDeferred) {
            val bottomMargin = calculateBottomMargin(
                windowInsets.keyboardInsets.bottom,
                getNavbarHeight(),
            )
            updateTargetBottomMargin(bottomMargin)
            onIMEAnimationFinished(isKeyboardShowingUp, bottomMargin)
        }

        return windowInsets
    }

    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
        if (animation.typeMask and ime() != 0) {
            areKeyboardInsetsDeferred = true
        }
    }

    override fun onStart(
        animation: WindowInsetsAnimationCompat,
        bounds: WindowInsetsAnimationCompat.BoundsCompat,
    ): WindowInsetsAnimationCompat.BoundsCompat {
        if (animation.typeMask and ime() != 0) {
            keyboardAnimationInProgress = true
            keyboardHeight = bounds.upperBound.bottom - bounds.lowerBound.bottom
            if (keyboardHeight <= getNavbarHeight()) {
                keyboardHeight = 0
            }
            onIMEAnimationStarted(
                isKeyboardShowingUp,
                calculateBottomMargin(keyboardHeight, getNavbarHeight()),
            )
        }

        return super.onStart(animation, bounds)
    }

    override fun onProgress(
        insets: WindowInsetsCompat,
        runningAnimations: List<WindowInsetsAnimationCompat>,
    ): WindowInsetsCompat {
        if (!keyboardAnimationInProgress) return insets

        runningAnimations
            .firstOrNull { it.typeMask and ime() != 0 }
            ?.let { imeAnimation ->
                val imeAnimationFractionBasedOnDirection = when (isKeyboardShowingUp) {
                    true -> imeAnimation.interpolatedFraction
                    false -> 1 - imeAnimation.interpolatedFraction
                }
                updateTargetBottomMargin(
                    calculateBottomMargin(
                        (keyboardHeight * imeAnimationFractionBasedOnDirection).toInt(),
                        getNavbarHeight(),
                    ),
                )
            }

        return insets
    }

    override fun onEnd(animation: WindowInsetsAnimationCompat) {
        keyboardAnimationInProgress = false

        val currentInsets = getCurrentInsets()
        if (currentInsets != null && areKeyboardInsetsDeferred && (animation.typeMask and ime()) != 0) {
            areKeyboardInsetsDeferred = false
            ViewCompat.dispatchApplyWindowInsets(insetsSource, currentInsets)
        }
    }

    private val WindowInsetsCompat.keyboardInsets
        get() = getInsets(ime())

    private val WindowInsetsCompat.isKeyboardShowingUp
        get() = isVisible(ime())

    private val WindowInsetsCompat.navigationBarInsetHeight
        get() = when (isKeyboardShowingUp) {
            true -> getInsets(systemBars()).bottom
            false -> 0
        }

    private fun getNavbarHeight(): Int =
        ViewCompat.getRootWindowInsets(insetsSource)?.getInsets(systemBars())?.bottom
            ?: if (::lastWindowInsets.isInitialized) {
                lastWindowInsets.navigationBarInsetHeight
            } else {
                0
            }

    private fun getCurrentInsets(): WindowInsetsCompat? =
        if (::lastWindowInsets.isInitialized) {
            lastWindowInsets
        } else {
            ViewCompat.getRootWindowInsets(insetsSource)
        }

    private fun calculateBottomMargin(
        keyboardHeight: Int,
        navigationBarHeight: Int,
    ): Int = (keyboardHeight - navigationBarHeight).coerceAtLeast(0)

    private fun updateTargetBottomMargin(bottom: Int) {
        if (synchronizeViewWithIME) {
            targetView.updateBottomMarginIfChanged(bottom)
        }
    }

    /** [insetsSource] へのリスナー登録を解除し、margin を戻す。 */
    fun detach() {
        ViewCompat.setWindowInsetsAnimationCallback(insetsSource, null)
        ViewCompat.setOnApplyWindowInsetsListener(insetsSource, null)
        targetView.updateBottomMarginIfChanged(0)
    }

    companion object {
        /**
         * @param targetView bottomMargin を更新する View (SwipeRefreshLayout 等)
         * @param insetsSource WindowInsets を受け取る View。Compose 配下では decorView を推奨。
         */
        fun setup(
            targetView: View,
            insetsSource: View = targetView,
            synchronizeViewWithIME: Boolean = true,
            onIMEAnimationStarted: (Boolean, Int) -> Unit = { _, _ -> },
            onIMEAnimationFinished: (Boolean, Int) -> Unit = { _, _ -> },
        ): ImeInsetsSynchronizer =
            ImeInsetsSynchronizer(
                targetView,
                insetsSource,
                synchronizeViewWithIME,
                onIMEAnimationStarted,
                onIMEAnimationFinished,
            )
    }
}

internal fun View.updateBottomMarginIfChanged(bottom: Int) {
    val current = layoutParams
    val marginParams = when (current) {
        is ViewGroup.MarginLayoutParams -> current
        else -> ViewGroup.MarginLayoutParams(
            current.width,
            current.height,
        ).also { layoutParams = it }
    }
    if (marginParams.bottomMargin == bottom) return
    marginParams.bottomMargin = bottom
    requestLayout()
}
