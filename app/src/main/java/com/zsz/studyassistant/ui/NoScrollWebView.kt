package com.zsz.studyassistant.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

/**
 * 不消费触摸事件的 WebView：内容正常渲染，但不会拦截竖向拖动，
 * 从而把滚动手势透传给外层 LazyColumn（修复答案区域无法上下滑动的问题）。
 */
class NoScrollWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 不消费触摸，交由父级容器处理滚动
        return false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean = false
}
