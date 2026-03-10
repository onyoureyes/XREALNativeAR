package com.xreal.nativear.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.junit.Assert.*

class GlobalEventBusTest {

    @Test
    fun `이벤트 발행 후 구독자가 수신한다`() = runTest {
        val bus = GlobalEventBus()
        val received = mutableListOf<XRealEvent>()

        val job = launch { bus.events.collect { received.add(it) } }
        advanceUntilIdle() // collector 활성화 대기

        bus.publish(XRealEvent.SystemEvent.DebugLog("test message"))
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertTrue(received[0] is XRealEvent.SystemEvent.DebugLog)
        job.cancel()
    }

    @Test
    fun `다중 이벤트 순서 보장`() = runTest {
        val bus = GlobalEventBus()
        val received = mutableListOf<String>()

        val job = launch {
            bus.events.collect { event ->
                if (event is XRealEvent.SystemEvent.DebugLog) {
                    received.add(event.message)
                }
            }
        }
        advanceUntilIdle()

        bus.publish(XRealEvent.SystemEvent.DebugLog("first"))
        bus.publish(XRealEvent.SystemEvent.DebugLog("second"))
        bus.publish(XRealEvent.SystemEvent.DebugLog("third"))
        advanceUntilIdle()

        assertEquals(listOf("first", "second", "third"), received)
        job.cancel()
    }

    @Test
    fun `replay=0이므로 구독 전 이벤트는 수신하지 않는다`() = runTest {
        val bus = GlobalEventBus()

        // 구독 전에 발행
        bus.publish(XRealEvent.SystemEvent.DebugLog("before subscribe"))
        advanceUntilIdle()

        val received = mutableListOf<XRealEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        advanceUntilIdle()

        assertEquals(0, received.size)
        job.cancel()
    }

    @Test
    fun `다중 구독자 모두 이벤트 수신`() = runTest {
        val bus = GlobalEventBus()
        val receivedA = mutableListOf<XRealEvent>()
        val receivedB = mutableListOf<XRealEvent>()

        val jobA = launch { bus.events.collect { receivedA.add(it) } }
        val jobB = launch { bus.events.collect { receivedB.add(it) } }
        advanceUntilIdle()

        bus.publish(XRealEvent.SystemEvent.DebugLog("shared event"))
        advanceUntilIdle()

        assertEquals(1, receivedA.size)
        assertEquals(1, receivedB.size)
        jobA.cancel()
        jobB.cancel()
    }

    @Test
    fun `publish는 tryEmit 사용 - 논블로킹`() = runTest {
        val bus = GlobalEventBus()
        // publish가 블로킹되지 않고 즉시 반환
        bus.publish(XRealEvent.SystemEvent.DebugLog("non-blocking"))
        // 예외 없이 완료되면 성공
    }

    @Test
    fun `다양한 이벤트 타입 동시 발행`() = runTest {
        val bus = GlobalEventBus()
        val received = mutableListOf<XRealEvent>()

        val job = launch { bus.events.collect { received.add(it) } }
        advanceUntilIdle()

        bus.publish(XRealEvent.SystemEvent.DebugLog("system"))
        bus.publish(XRealEvent.InputEvent.VoiceCommand("hello"))
        bus.publish(XRealEvent.ActionRequest.SpeakTTS("speak this"))
        advanceUntilIdle()

        assertEquals(3, received.size)
        assertTrue(received[0] is XRealEvent.SystemEvent.DebugLog)
        assertTrue(received[1] is XRealEvent.InputEvent.VoiceCommand)
        assertTrue(received[2] is XRealEvent.ActionRequest.SpeakTTS)
        job.cancel()
    }

    @Test
    fun `구독 취소 후 이벤트 수신하지 않음`() = runTest {
        val bus = GlobalEventBus()
        val received = mutableListOf<XRealEvent>()

        val job = launch { bus.events.collect { received.add(it) } }
        advanceUntilIdle()

        bus.publish(XRealEvent.SystemEvent.DebugLog("before cancel"))
        advanceUntilIdle()

        job.cancel()
        advanceUntilIdle()

        bus.publish(XRealEvent.SystemEvent.DebugLog("after cancel"))
        advanceUntilIdle()

        assertEquals(1, received.size)
    }

    @Test
    fun `빈 이벤트 버스 구독 시 블로킹 없음`() = runTest {
        val bus = GlobalEventBus()
        val received = mutableListOf<XRealEvent>()

        val job = launch { bus.events.collect { received.add(it) } }
        advanceUntilIdle()

        assertEquals(0, received.size)
        job.cancel()
    }
}
