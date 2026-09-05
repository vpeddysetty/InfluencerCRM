package com.influencer.dao.creator.api;

import com.influencer.dao.creator.domain.Creator;
import com.influencer.dao.creator.infrastructure.CreatorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which query a creator listing runs, and what counts as a filter (roadmap PR-67).
 *
 * <p>Two decisions are worth pinning. <b>A blank parameter is not a filter</b> — an empty search box
 * submits {@code ""}, and treating that as a term would match on the empty string and quietly
 * return a subset of the roster while looking like a search that worked. <b>And filters only apply
 * with a brand</b>: searching every brand on the platform at once is not a question this endpoint
 * should answer, and letting the filters through without a tenant scope would make it one.
 *
 * <p>Asserted against the routing rather than by executing SQL: the query itself is verified
 * against real Postgres (the casts, the enum, the inclusive bounds), and what a unit test can
 * usefully hold is the decision about which path a request takes.
 */
class CreatorSearchRoutingTest {

    private static final UUID BRAND = UUID.randomUUID();

    /** Records which repository method the controller chose, and with what. */
    private static final class RecordingRepository implements java.lang.reflect.InvocationHandler {
        String called;
        Object[] args;

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            called = method.getName();
            args = arguments;
            return List.<Creator>of();
        }
    }

    private CreatorController controllerFor(RecordingRepository recorder) {
        CreatorRepository repository = (CreatorRepository) java.lang.reflect.Proxy.newProxyInstance(
                CreatorRepository.class.getClassLoader(),
                new Class<?>[]{CreatorRepository.class},
                recorder);
        return new CreatorController(repository);
    }

    @Test
    @DisplayName("a search term routes to the filtered query")
    void searchTermUsesSearch() {
        RecordingRepository recorder = new RecordingRepository();

        controllerFor(recorder).findAll(BRAND, null, "bea", null, null, null, null);

        assertEquals("search", recorder.called);
    }

    @Test
    @DisplayName("an EMPTY search box is not a filter — the whole roster comes back")
    void blankTermIsNotAFilter() {
        // The failure this prevents: LIKE '%%' matches everything, so a blank term would look like
        // it worked while silently changing which query ran and how the rows were ordered.
        RecordingRepository recorder = new RecordingRepository();

        controllerFor(recorder).findAll(BRAND, null, "   ", null, null, null, null);

        assertEquals("search", recorder.called, "a blank term still takes the search path...");
        assertNull(recorder.args[1], "...but must reach the query as null, not as whitespace");
    }

    @Test
    @DisplayName("no filters and no brand still lists everything, as before")
    void unfilteredIsUnchanged() {
        RecordingRepository recorder = new RecordingRepository();

        controllerFor(recorder).findAll(null, null, null, null, null, null, null);

        assertEquals("findAll", recorder.called);
    }

    @Test
    @DisplayName("filters without a brand do NOT reach the search — that would search the platform")
    void filtersNeedABrand() {
        RecordingRepository recorder = new RecordingRepository();

        controllerFor(recorder).findAll(null, null, "bea", null, null, null, null);

        assertEquals("findAll", recorder.called,
                "a filter with no tenant scope must not become a cross-brand search");
    }

    @Test
    @DisplayName("a follower bound alone is a filter")
    void followerBoundIsAFilter() {
        // Worth its own case: the bound is an Integer, so a null check rather than a blank check
        // decides it, and 0 is a legitimate lower bound that must not read as absent.
        RecordingRepository recorder = new RecordingRepository();

        controllerFor(recorder).findAll(BRAND, null, null, null, null, 0, null);

        assertEquals("search", recorder.called);
        assertEquals(0, recorder.args[5]);
    }

    @Test
    @DisplayName("the brand always reaches the query first, whatever else is filtered")
    void brandIsAlwaysScoped() {
        RecordingRepository recorder = new RecordingRepository();

        controllerFor(recorder).findAll(BRAND, "approved", "bea", "beauty", "instagram", 100, 200);

        assertEquals("search", recorder.called);
        assertEquals(BRAND, recorder.args[0], "the tenant scope is the first argument, always");
        assertTrue(List.of(recorder.args).contains("beauty"));
    }
}
