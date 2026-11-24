package com.tamada.travel.orchestrator.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Стратегия добавления случайного разброса (Jitter) к задержке повторных попыток (Retry).
 * Предотвращает лавинообразные одновременные повторные запросы (thundering herd problem)
 * к внешним поставщикам при сетевых задержках или сбоях.
 */
@FunctionalInterface
public interface JitterStrategy {

    /**
     * Без добавления джиттера (детерминированная задержка).
     */
    JitterStrategy NO_JITTER = delay -> delay;

    /**
     * Full Jitter: случайная задержка в диапазоне от 0.5 * delay до delay.
     */
    JitterStrategy FULL_JITTER = delay -> {
        long millis = delay.toMillis();
        if (millis <= 0) {
            return Duration.ZERO;
        }
        long min = Math.max(1, millis / 2);
        long jittered = ThreadLocalRandom.current().nextLong(min, millis + 1);
        return Duration.ofMillis(jittered);
    };

    Duration computeDelay(Duration exponentialDelay);
}
