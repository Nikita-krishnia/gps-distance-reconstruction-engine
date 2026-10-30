package com.example.gpsengine.testutil;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe in-memory Logback appender to capture application and root log events
 * for privacy, redaction, and error logging verification tests.
 */
public class InMemoryLogAppender extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private final List<ILoggingEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final Logger rootLogger;

    public InMemoryLogAppender() {
        this.rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        start();
        this.rootLogger.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        events.add(eventObject);
    }

    public List<ILoggingEvent> getEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    public List<String> getFormattedMessages() {
        synchronized (events) {
            List<String> messages = new ArrayList<>(events.size());
            for (ILoggingEvent event : events) {
                messages.add(event.getFormattedMessage());
            }
            return messages;
        }
    }

    public String getAllLogsJoined() {
        synchronized (events) {
            StringBuilder sb = new StringBuilder();
            for (ILoggingEvent event : events) {
                sb.append(event.getFormattedMessage()).append("\n");
                if (event.getThrowableProxy() != null) {
                    sb.append(event.getThrowableProxy().getClassName())
                            .append(": ")
                            .append(event.getThrowableProxy().getMessage())
                            .append("\n");
                }
            }
            return sb.toString();
        }
    }

    public void clear() {
        events.clear();
    }

    @Override
    public void close() {
        stop();
        if (rootLogger != null) {
            rootLogger.detachAppender(this);
        }
    }
}

