package com.parkingroom.app.car

import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Vehicle SDK의 onComplete/onFailed 콜백을 suspend 호출로 바꾼다. 응답이 없으면 timeout 실패. */
internal suspend fun <T> awaitCar(
    timeoutMs: Long = 3000,
    block: (ok: (T) -> Unit, err: (Exception) -> Unit) -> Unit,
): Result<T> = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine<Result<T>> { c ->
        block({ if (c.isActive) c.resume(Result.success(it)) }, { if (c.isActive) c.resume(Result.failure(it)) })
    }
} ?: Result.failure(TimeoutException("vehicle call timeout"))

/** SDK 2.3.2의 VehicleException은 난독화돼 있어 message(ErrorCode 이름)로 판별한다 */
internal fun Throwable.isPermanentFailure(): Boolean =
    this is SecurityException || this is IllegalArgumentException ||
        message?.contains("NOT_SUPPORTED") == true || message?.contains("SECURITY") == true
