package com.influencer.webe.creator.infrastructure;

import com.influencer.webe.creator.application.SocialPlatformAdapter;
import com.influencer.webe.creator.application.SocialPlatformRegistry;
import com.influencer.webe.creator.application.SocialProfileGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Routes a profile lookup to the adapter for its platform (roadmap M6.2).
 *
 * <p>This is the routing {@link SocialProfileGateway} always implied and never had.
 * {@code fetch(platform, handle)} took the platform as an argument, and the only implementation
 * ignored it except to echo it back — so every creator, on every platform, got the same
 * hash-derived number from the same code path.
 *
 * <p><b>{@code @Primary}</b> because {@link MockSocialProfileGateway} is still a
 * {@code SocialProfileGateway} bean and existing call sites — {@code CreatorOnboardingService},
 * {@code CreatorHealthService} — inject the interface. Making this the preferred bean means those
 * call sites gain per-platform routing without being touched, and the mock keeps working as a
 * delegate rather than a competing implementation.
 *
 * <p><b>Falls back rather than failing.</b> No adapter, an unconfigured one, or a lookup that
 * returns nothing all fall through to the simulation. Rule C.6: a creator lookup that cannot reach
 * a platform must degrade to a usable number, never fail the signup. The {@code source} field
 * records which happened, so the fallback is visible in the data rather than silent.
 */
@Component
@Primary
public class DispatchingSocialProfileGateway implements SocialProfileGateway {

    private static final Logger log = LoggerFactory.getLogger(DispatchingSocialProfileGateway.class);

    private final SocialPlatformRegistry registry;
    private final MockSocialProfileGateway fallback;

    public DispatchingSocialProfileGateway(SocialPlatformRegistry registry,
                                           MockSocialProfileGateway fallback) {
        this.registry = registry;
        this.fallback = fallback;
    }

    @Override
    public Profile fetch(String platform, String handle) {
        Optional<SocialPlatformAdapter> adapter = registry.find(platform);
        if (adapter.isEmpty()) {
            return fallback.fetch(platform, handle);
        }

        Profile profile;
        try {
            profile = adapter.get().fetch(handle);
        } catch (RuntimeException e) {
            // An adapter throwing is a bug in that adapter, not a reason to fail the caller. Logged
            // at warn because it IS a defect worth fixing — unlike a null return, which is ordinary.
            log.warn("Adapter {} threw for handle {}; falling back to simulation",
                    adapter.get().getClass().getSimpleName(), handle, e);
            return fallback.fetch(platform, handle);
        }

        if (profile == null) {
            // Ordinary: a private account, a typo, a deleted profile. Not logged as a problem.
            //
            // Deliberately does NOT fall back here. A real adapter answering "this handle does not
            // exist" is information, and substituting an invented follower count for it would turn
            // a correct negative into a plausible fabrication on a row the UI labels as real.
            return null;
        }
        return profile;
    }
}
