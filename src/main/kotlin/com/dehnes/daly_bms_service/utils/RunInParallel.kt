package com.dehnes.daly_bms_service.utils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

fun <Input, Output> runInParallel(
    executorService: ExecutorService,
    items: Collection<Input>,
    mapper: (item: Input) -> Output?
): List<Output> {

    val results = mutableListOf<Output>()
    val errors = mutableListOf<Throwable>()
    val c = CountDownLatch(items.size)
    items.forEach { item ->
        executorService.submit {
            try {
                val r = mapper(item)
                if (r != null) {
                    synchronized(results) {
                        results.add(r)
                    }
                }

                c.countDown()
            } catch (e: Throwable) {
                synchronized(errors) {
                    errors.add(e)
                }
            }
        }
    }

    check(c.await(60, TimeUnit.SECONDS))

    if (errors.isNotEmpty()) throw errors.first()
    return results

}