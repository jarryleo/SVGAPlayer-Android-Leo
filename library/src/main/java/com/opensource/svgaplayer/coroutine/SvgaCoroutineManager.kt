package com.opensource.svgaplayer.coroutine

import com.opensource.svgaplayer.utils.log.LogUtils
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.ThreadPoolExecutor

/**
 * @Author     :Leo
 * Date        :2024/6/25
 * Description : 协程管理器
 */

object SvgaCoroutineManager {
    /**
     * 协程异常处理
     */
    private val coroutineExceptionHandler =
        CoroutineExceptionHandler { coroutineContext, throwable ->
            LogUtils.debug(
                "SvgaCoroutineManager",
                "coroutineContext $coroutineContext, error msg : ${throwable.message}"
            )
            throwable.printStackTrace()
        }

    private var dispatcher: ExecutorCoroutineDispatcher? = null

    private val job = Job()

    /**
     * 协程作用域
     */
    private val scope = CoroutineScope(job)

    /**
     * 设置自定义线程池
     */
    @JvmStatic
    internal fun setThreadPoolExecutor(threadPoolExecutor: ThreadPoolExecutor) {
        dispatcher = threadPoolExecutor.asCoroutineDispatcher()
    }

    /**
     * 启动协程，优先采用自定义线程池，没有则使用共享IO线程池
     */
    fun launchIo(
        handler: CoroutineExceptionHandler = coroutineExceptionHandler,
        childJob: Job = SupervisorJob(this.job),
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        return scope.launch(
            (dispatcher ?: Dispatchers.IO) + handler + childJob
        ) { block.invoke(this) }
    }

    /**
     * 在主线程启动协程
     */
    fun launchMain(
        handler: CoroutineExceptionHandler = coroutineExceptionHandler,
        childJob: Job = SupervisorJob(this.job),
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        return scope.launch(Dispatchers.Main + handler + childJob) { block.invoke(this) }
    }
}