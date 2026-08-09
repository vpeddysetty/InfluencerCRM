package com.influencer.webe.shared.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.encoder.EncoderBase;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Renders each log event as one line of JSON, for file-scraping tools to parse.
 *
 * <p><b>Why hand-written rather than logstash-logback-encoder.</b> That library is the usual
 * answer, but it is not in this build and adding a dependency to four services to format a string
 * is a poor trade. This is ~120 lines with no transitive dependencies, and the format is ours to
 * keep stable.
 *
 * <p><b>One line per event, always.</b> A scraper splits on newlines, so an embedded newline —
 * overwhelmingly from a stack trace — would be read as several malformed records and typically
 * dropped. Every value is escaped, and the stack trace is folded into a single field with
 * {@code \n} escaped rather than emitted raw. Losing the one log line that explains an outage
 * because it contained a stack trace is the specific failure this prevents.
 *
 * <p><b>It cannot throw.</b> {@link #encode} is called on every log statement in the application;
 * an exception here would propagate into unrelated business code and turn a logging bug into an
 * outage. Any failure degrades to a minimal record that still parses, so the scraper sees that
 * something happened rather than nothing.
 */
public class JsonLogEncoder extends EncoderBase<ILoggingEvent> {

    /** Guards against a pathological message filling the disk or the scraper's buffer. */
    private static final int MAX_MESSAGE = 8_000;
    private static final int MAX_STACK_FRAMES = 30;

    private String service = "unknown";

    /** Set from logback.xml, so one encoder class serves every service. */
    public void setService(String service) {
        this.service = service == null || service.isBlank() ? "unknown" : service;
    }

    public String getService() {
        return service;
    }

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        try {
            return render(event).getBytes(StandardCharsets.UTF_8);
        } catch (Exception failure) {
            // Never propagate. A broken log line must not break the request that produced it.
            return ("{\"ts\":\"" + Instant.now() + "\",\"level\":\"ERROR\",\"svc\":\"" + service
                    + "\",\"evt\":\"log.encode.failed\",\"msg\":\"log event could not be encoded\"}\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private String render(ILoggingEvent event) {
        StringBuilder out = new StringBuilder(512);
        out.append('{');

        // Fixed fields first, in a stable order — it makes the raw file readable by eye during an
        // incident, which is when someone is most likely to be reading it without a tool.
        field(out, "ts", Instant.ofEpochMilli(event.getTimeStamp()).toString(), true);
        field(out, "level", event.getLevel().toString(), false);
        field(out, "svc", service, false);

        // MDC next: rid/app/tenant/user/evt. These are the query keys.
        Map<String, String> context = event.getMDCPropertyMap();
        if (context != null) {
            for (Map.Entry<String, String> entry : context.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    field(out, entry.getKey(), entry.getValue(), false);
                }
            }
        }

        field(out, "logger", shortLogger(event.getLoggerName()), false);
        field(out, "thread", event.getThreadName(), false);
        field(out, "msg", truncate(event.getFormattedMessage(), MAX_MESSAGE), false);

        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            field(out, "exception", thrown.getClassName(), false);
            field(out, "exceptionMessage", truncate(thrown.getMessage(), 1_000), false);
            field(out, "stack", stackOf(thrown), false);
        }

        out.append("}\n");
        return out.toString();
    }

    private void field(StringBuilder out, String key, String value, boolean first) {
        if (value == null) {
            return;
        }
        if (!first && out.length() > 1) {
            out.append(',');
        }
        out.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"');
    }

    /**
     * Escapes to JSON string rules.
     *
     * <p>The control-character branch matters more than it looks: a stack trace or a quoted user
     * value containing a raw newline or tab would otherwise produce a record the scraper cannot
     * parse — and the lines worth reading are exactly the ones carrying exception detail.
     */
    private String escape(String raw) {
        StringBuilder escaped = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Flattens a throwable and its causes into one field.
     *
     * <p>Frame-capped deliberately. An unbounded trace from a deep framework stack can run to
     * hundreds of lines; at volume that is most of the log file's bytes, and the frames that
     * identify the fault are the first ones.
     */
    private String stackOf(IThrowableProxy thrown) {
        StringBuilder stack = new StringBuilder(1_024);
        IThrowableProxy current = thrown;
        int depth = 0;

        while (current != null && depth < 3) {
            if (depth > 0) {
                stack.append("Caused by: ");
            }
            stack.append(current.getClassName());
            if (current.getMessage() != null) {
                stack.append(": ").append(current.getMessage());
            }
            stack.append('\n');

            StackTraceElementProxy[] frames = current.getStackTraceElementProxyArray();
            if (frames != null) {
                int limit = Math.min(frames.length, MAX_STACK_FRAMES);
                for (int i = 0; i < limit; i++) {
                    stack.append("\tat ").append(frames[i].getStackTraceElement()).append('\n');
                }
                if (frames.length > limit) {
                    stack.append("\t... ").append(frames.length - limit).append(" more\n");
                }
            }
            current = current.getCause();
            depth++;
        }
        return stack.toString();
    }

    /** {@code com.influencer.webe.marketplace.CredentialProtector} → {@code c.i.w.m.CredentialProtector}. */
    private String shortLogger(String name) {
        if (name == null) {
            return null;
        }
        int lastDot = name.lastIndexOf('.');
        if (lastDot < 0) {
            return name;
        }
        StringBuilder shortened = new StringBuilder(name.length());
        for (String part : name.substring(0, lastDot).split("\\.")) {
            if (!part.isEmpty()) {
                shortened.append(part.charAt(0)).append('.');
            }
        }
        return shortened.append(name.substring(lastDot + 1)).toString();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…[truncated]";
    }
}
