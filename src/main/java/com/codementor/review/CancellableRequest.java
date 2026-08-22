package com.codementor.review;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Future;

/** 可由协调器取消的后台模型请求。 */
public interface CancellableRequest {

    void cancel();

    boolean isCancelled();

    static CancellableRequest forThread(Thread worker) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        return new CancellableRequest() {
            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    worker.interrupt();
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    static CancellableRequest forFuture(Future<?> future) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        return new CancellableRequest() {
            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    future.cancel(true);
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get() || future.isCancelled();
            }
        };
    }
}
