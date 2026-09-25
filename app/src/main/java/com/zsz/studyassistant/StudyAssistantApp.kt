package com.zsz.studyassistant

import android.app.Application
import com.zsz.studyassistant.data.CrashLogger

/**
 * 自定义 Application：目前只做一件事 —— 尽早安装崩溃日志兜底。
 *
 * 放在 Application（而不是 MainActivity）里，是为了连**启动阶段**的崩溃也能记下来；
 * 已上架应用的 Application 不适合做重活，这里只挂一个异常处理器，开销可忽略。
 */
class StudyAssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
