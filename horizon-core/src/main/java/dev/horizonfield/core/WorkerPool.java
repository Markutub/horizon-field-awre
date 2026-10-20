package dev.horizonfield.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Fixed pool of daemon threads consuming a {@link BoundedTaskQueue}. Handler exceptions are counted, never fatal. */
public final class WorkerPool<T> {
    private final BoundedTaskQueue<T> queue;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = true;

    public WorkerPool(String name, int threadCount, BoundedTaskQueue<T> queue, Consumer<T> handler, Metrics metrics,
                      Consumer<Throwable> errorSink) {
        this.queue = queue;
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(() -> {
                while (running) {
                    try {
                        T item = queue.poll(250);
                        if (item == null) continue;
                        handler.accept(item);
                        metrics.inc("worker.done");
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Throwable ex) {
                        metrics.inc("worker.errors");
                        errorSink.accept(ex);
                    }
                }
            }, name + "-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
    }

    /** Stops accepting work, wakes workers, waits up to timeoutMillis total. Returns true if all threads exited. */
    public boolean shutdown(long timeoutMillis) {
        running = false;
        queue.close();
        long end = System.nanoTime() + timeoutMillis * 1_000_000L;
        for (Thread t : threads) {
            try {
                long left = Math.max(1, (end - System.nanoTime()) / 1_000_000L);
                t.join(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return threads.stream().noneMatch(Thread::isAlive);
    }
}
