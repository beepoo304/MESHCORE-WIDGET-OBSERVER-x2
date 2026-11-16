package pl.meshcore.observerwidget

/** Pure WDG state machine. Times are wall-clock milliseconds. */
internal object WdgEngine {
    enum class Status { UNKNOWN, ONLINE, REFRESH, ERROR }
    enum class Pending { NONE, DOWN, RESTORE }
    enum class Action { NONE, ERROR, RESTORE }

    data class State(
        val status: Status = Status.UNKNOWN,
        val pending: Pending = Pending.NONE,
        val nextCheckAtMs: Long = 0L,
        val onlineSinceMs: Long = 0L,
        val outageStartedAtMs: Long = 0L
    )

    data class Result(val state: State, val action: Action)

    fun onCheck(state: State, success: Boolean, nowMs: Long, intervalMs: Long): Result {
        val normalNext = nowMs + intervalMs
        return when (state.status) {
            Status.UNKNOWN -> if (success) {
                Result(state.copy(status = Status.ONLINE, pending = Pending.NONE,
                    nextCheckAtMs = normalNext, onlineSinceMs = nowMs), Action.NONE)
            } else {
                Result(state.copy(status = Status.REFRESH, pending = Pending.DOWN,
                    nextCheckAtMs = nowMs + CONFIRM_MS), Action.NONE)
            }
            Status.ONLINE -> if (success) {
                Result(state.copy(nextCheckAtMs = normalNext), Action.NONE)
            } else {
                Result(state.copy(status = Status.REFRESH, pending = Pending.DOWN,
                    nextCheckAtMs = nowMs + CONFIRM_MS), Action.NONE)
            }
            Status.REFRESH -> when (state.pending) {
                Pending.DOWN -> if (success) {
                    Result(state.copy(status = Status.ONLINE, pending = Pending.NONE,
                        nextCheckAtMs = normalNext,
                        onlineSinceMs = if (state.onlineSinceMs == 0L) nowMs else state.onlineSinceMs), Action.NONE)
                } else {
                    Result(state.copy(status = Status.ERROR, pending = Pending.NONE,
                        nextCheckAtMs = nowMs + ERROR_RETRY_MS, outageStartedAtMs = nowMs), Action.ERROR)
                }
                Pending.RESTORE -> if (success) {
                    Result(state.copy(status = Status.ONLINE, pending = Pending.NONE,
                        nextCheckAtMs = normalNext, onlineSinceMs = nowMs, outageStartedAtMs = 0L), Action.RESTORE)
                } else {
                    Result(state.copy(status = Status.ERROR, pending = Pending.NONE,
                        nextCheckAtMs = nowMs + ERROR_RETRY_MS), Action.NONE)
                }
                Pending.NONE -> if (success) {
                    Result(state.copy(status = Status.ONLINE, nextCheckAtMs = normalNext), Action.NONE)
                } else {
                    Result(state.copy(status = Status.REFRESH, pending = Pending.DOWN,
                        nextCheckAtMs = nowMs + CONFIRM_MS), Action.NONE)
                }
            }
            Status.ERROR -> if (success) {
                Result(state.copy(status = Status.REFRESH, pending = Pending.RESTORE,
                    nextCheckAtMs = nowMs + CONFIRM_MS), Action.NONE)
            } else {
                Result(state.copy(nextCheckAtMs = nowMs + ERROR_RETRY_MS), Action.NONE)
            }
        }
    }

    const val CONFIRM_MS = 15_000L
    const val ERROR_RETRY_MS = 5 * 60_000L
}
