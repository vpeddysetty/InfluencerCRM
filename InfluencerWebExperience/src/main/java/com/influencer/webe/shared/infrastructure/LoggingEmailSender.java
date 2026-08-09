package com.influencer.webe.shared.infrastructure;

import com.influencer.webe.shared.application.EmailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Log-only email delivery — the default when no provider is configured.
 *
 * <p><b>Why this exists rather than requiring Postmark.</b> Same reasoning as
 * {@code FilesystemAssetStorage}: the invitation flow, welcome emails (M4.3) and expiry warnings
 * (M5.6) all depend on the port, and gating them on a provider account would mean none of them
 * could be built or tested until that account existed. A real adapter takes over by configuration
 * with no change to any caller.
 *
 * <p><b>It declares itself.</b> {@link #provider()} returns {@code "log"} and every send is logged
 * at WARN with an explicit "NOT SENT" marker. The alternative — returning
 * {@code Result.sent("postmark", …)} from something that sends nothing — is the failure mode
 * {@code MockDomainRegistrar}'s Javadoc warns about: a mock claiming to be a real provider puts a
 * simulated result in front of a user as though the real thing had happened. An invitation that
 * silently never arrives is worse than one that visibly failed, because nobody investigates it.
 *
 * <p>Not intended for production. When {@code WEBE_EMAIL_PROVIDER} is unset in a deployed
 * environment, invitations reach nobody — which is why the log line is WARN rather than DEBUG.
 */
@Component
@ConditionalOnProperty(name = "web-experience.email.provider", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements EmailPort {
    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private static final String PROVIDER = "log";

    @Override
    public Result send(Message message) {
        if (message == null || message.to() == null || message.to().isBlank()) {
            // Reported, not thrown — see EmailPort.send. A malformed recipient is a caller bug,
            // but failing the surrounding request over it would roll back work already done.
            log.warn("[email:{}] NOT SENT — no recipient address", PROVIDER);
            return Result.failed(PROVIDER, "no recipient address");
        }

        String messageId = UUID.randomUUID().toString();

        // The body is logged so a developer can follow an invitation link from the console
        // without a mail server. That is the whole point of this adapter — and also why it must
        // never be the provider in an environment handling real users, since bodies carry
        // one-time tokens and application logs are not a secret store.
        log.warn("""
                [email:{}] NOT SENT — no provider configured. Set web-experience.email.provider.
                  id:      {}
                  to:      {}
                  subject: {}
                  body:
                {}""",
                PROVIDER, messageId, message.to(), message.subject(), indent(message.textBody()));

        return Result.sent(PROVIDER, messageId);
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    /** Indents the body so a multi-line email is visually distinct from surrounding log lines. */
    private static String indent(String body) {
        if (body == null || body.isBlank()) {
            return "    (empty)";
        }
        return body.stripTrailing().indent(4).stripTrailing();
    }
}
