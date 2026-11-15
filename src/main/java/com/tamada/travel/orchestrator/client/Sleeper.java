package com.tamada.travel.orchestrator.client;

import java.time.Duration;

@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    Sleeper DEFAULT = duration -> Thread.sleep(duration.toMillis());
}
