package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerSettings
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Http2PolicyTest {

    @Test
    fun `playback clients speak http 1_1 only`() {
        val client = NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(OkHttpClient.Builder()).build()
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
        assertSame(NuvioExoPlayerPerformanceHelper.sharedConnectionPool, client.connectionPool)
    }

    @Test
    fun `base playback clients and the tls fallback speak http 1_1 only`() {
        assertEquals(listOf(Protocol.HTTP_1_1), PlayerPlaybackNetworking.playbackHttpClient.protocols)
        assertEquals(listOf(Protocol.HTTP_1_1), PlayerPlaybackNetworking.trustAllPlaybackHttpClient.protocols)
    }

    @Test
    fun `http2 stored by an older version is ignored`() {
        val stored = PlayerSettings(enableHttp2 = true, parallelNetworkEnabled = true, useParallelConnections = true)
        val factory = HttpLanes.callFactory(
            NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(OkHttpClient.Builder()).build(),
            connections = 4
        ) { okhttp3.ConnectionPool() }
        assertEquals(true, stored.enableHttp2)
        assertEquals(listOf(Protocol.HTTP_1_1), (factory as OkHttpClient).protocols)
    }
}
