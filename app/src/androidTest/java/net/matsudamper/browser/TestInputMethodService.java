package net.matsudamper.browser;

import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;

/**
 * テストで IME insets を発生させるための、固定高さのダミーキーボード。
 *
 * <p>GMD が使う aosp-atd イメージは LatinIME を含まないためソフトキーボードが存在せず、
 * キーボード表示を前提としたテストが成立しない。実キーボードの代わりにこのサービスを
 * 有効化して、決まった高さの IME insets を発生させる。
 *
 * <p>Kotlin ではなく Java で書いているのは、androidTest APK 単体のプロセスには
 * Kotlin ランタイムが無いため。Kotlin で書くと null チェックの
 * kotlin.jvm.internal.Intrinsics 解決に失敗して IME プロセスが即死する。
 */
public class TestInputMethodService extends InputMethodService {

    /** 失敗時の診断でログを絞り込むためのタグ */
    public static final String TAG = "BrowsemTestIme";

    /** 実キーボードに近い高さ。画面下部の入力欄を確実に覆う */
    public static final int KEYBOARD_HEIGHT_DP = 300;

    private static final int BACKGROUND_COLOR = 0xFF303030;

    private int keyboardHeightPx() {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                KEYBOARD_HEIGHT_DP,
                getResources().getDisplayMetrics());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
    }

    @Override
    public View onCreateInputView() {
        Log.i(TAG, "onCreateInputView height=" + keyboardHeightPx());
        View view = new View(this);
        view.setMinimumHeight(keyboardHeightPx());
        view.setBackgroundColor(BACKGROUND_COLOR);
        return view;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        Log.i(TAG, "onStartInputView restarting=" + restarting);
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        Log.i(TAG, "onWindowShown");
    }

    /** 横向きでも全画面 IME にせず、insets として観測できるようにする */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    /**
     * アプリへ通知する IME の高さを固定する。
     *
     * <p>入力ビューの測定に任せると IME ウィンドウが画面いっぱいに広がり、
     * insets が画面高さ相当になってしまうため、ここで直接指定する。
     */
    @Override
    public void onComputeInsets(Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (getWindow() == null || getWindow().getWindow() == null) {
            return;
        }
        int decorHeight = getWindow().getWindow().getDecorView().getHeight();
        int top = Math.max(0, decorHeight - keyboardHeightPx());
        outInsets.contentTopInsets = top;
        outInsets.visibleTopInsets = top;
    }
}
