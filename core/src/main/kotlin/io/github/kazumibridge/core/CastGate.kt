package io.github.kazumibridge.core

/** Accessed under the renderer's lock; generation ids reject stale conversion callbacks. */
class CastGate {
    enum class Phase { EMPTY, PREPARING, READY, FAILED, STOPPED }
    var generation = 0L; private set
    var phase = Phase.EMPTY; private set
    var wantsPlay = false; private set

    fun prepare(): Long { generation++; phase = Phase.PREPARING; wantsPlay = false; return generation }
    fun play(): Boolean { wantsPlay = true; return phase == Phase.READY }
    fun pause(): Boolean { wantsPlay = false; return phase == Phase.READY }
    fun ready(id: Long): Boolean { if (id != generation || phase != Phase.PREPARING) return false; phase = Phase.READY; return wantsPlay }
    fun fail(id: Long) { if (id == generation) { phase = Phase.FAILED; wantsPlay = false } }
    fun stop() { generation++; phase = Phase.STOPPED; wantsPlay = false }
    fun current(id: Long): Boolean = generation == id && phase !in setOf(Phase.STOPPED, Phase.FAILED)
}
