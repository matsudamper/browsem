package net.matsudamper.browser.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View

/**
 * [ContextThemeWrapper] 等でラップされた Context から [Activity] を取得する。
 * 直接キャストすると ClassCastException になるため、ラッパーを辿って解決する。
 */
fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current
        }
        current = current.baseContext
    }
    return null
}

fun View.findActivity(): Activity? = context.findActivity()
