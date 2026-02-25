package com.synapselib.androiddemo.di

import com.synapselib.arch.base.SwitchBoardReplayExpiration
import com.synapselib.arch.base.SwitchBoardScope
import com.synapselib.arch.base.SwitchBoardStopTimeout
import com.synapselib.arch.base.SwitchBoardWorkerContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

@InstallIn(SingletonComponent::class)
@Module
object SwitchBoardModule {
    val dispatcher = Dispatchers.IO

    @Provides
    @SwitchBoardStopTimeout
    fun provideStopTimeoutMillis(): Long = 3_000

    @Provides
    @SwitchBoardReplayExpiration
    fun provideReplayExpirationMillis(): Long = 3_000

    @Provides
    @SwitchBoardWorkerContext
    fun provideWorkerContext(): CoroutineContext = dispatcher

    @Provides
    @SwitchBoardScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}