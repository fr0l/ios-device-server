package com.badoo.automation.deviceserver.ios.simulator

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.newFixedThreadPoolContext
import java.util.concurrent.ScheduledThreadPoolExecutor

// lots of blocking and nothing memory consuming, so should be ok
val simulatorsThreadPool: ExecutorCoroutineDispatcher = newFixedThreadPoolContext(100, "Simulator_Thread")
val periodicTasksPool = ScheduledThreadPoolExecutor(4)
