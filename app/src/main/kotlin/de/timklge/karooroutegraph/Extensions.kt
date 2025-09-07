package de.timklge.karooroutegraph

import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


fun<T> Flow<T>.throttle(timeout: Long): Flow<T> = this
    .conflate()
    .transform {
        emit(it)
        delay(timeout)
    }

fun KarooSystemService.streamDatatypeIsVisible(
    datatype: String,
): Flow<Boolean> {
    return streamActiveRidePage().map { page ->
        page.page.elements.any { it.dataTypeId == datatype }
    }
}