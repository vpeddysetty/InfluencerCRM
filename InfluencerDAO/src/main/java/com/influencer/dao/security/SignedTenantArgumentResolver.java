package com.influencer.dao.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

/**
 * Overrides the {@code brandId} query parameter with the tenant the caller's token actually
 * asserts.
 *
 * <h2>Why here, and not in each controller</h2>
 *
 * <p>Twenty-four DAO controllers take {@code brandId} as an optional {@code @RequestParam} and, when
 * it is absent, fall back to an unfiltered query returning every tenant's rows. Editing all of them
 * would mean twenty-four chances to write the check slightly differently, and — worse — a
 * twenty-fifth controller written next month would silently not have it. The parameter-binding
 * layer is the one place every one of them passes through, so covering it once covers the ones
 * nobody has written yet.
 *
 * <p><b>It resolves the value rather than validating it.</b> A validating filter would have to
 * parse each route to know which parameter is the tenant; this simply supplies the right value at
 * the point the controller asks for one. The controller code is unchanged and unaware, which is
 * what makes it impossible to bypass by forgetting.
 *
 * <h2>What it does not do yet</h2>
 *
 * <p>When the caller presents no signed tenant — a legacy token, or an internal call that is
 * genuinely not tenant-scoped — the requested value is passed through unchanged. Refusing instead
 * would break every caller mid-migration. {@link CallerTenant} logs those cases; when they stop,
 * this can return null-and-refuse rather than falling back.
 *
 * <p><b>The unfiltered fallback inside each controller therefore still exists.</b> This narrows
 * <em>which</em> tenant a caller can name; it does not yet stop an unauthenticated-by-tenant call
 * from listing everything. Closing that needs the fallback removed per controller, which is a
 * behaviour change to each endpoint and belongs in its own change.
 */
public class SignedTenantArgumentResolver implements HandlerMethodArgumentResolver {

    /** Only this parameter name is intercepted; other UUIDs are ordinary arguments. */
    private static final String TENANT_PARAM = "brandId";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return UUID.class.equals(parameter.getParameterType())
                && TENANT_PARAM.equals(parameter.getParameterName());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        String requested = webRequest.getParameter(TENANT_PARAM);
        String resolved = CallerTenant.resolve(requested);

        if (resolved == null || resolved.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(resolved);
        } catch (IllegalArgumentException notAUuid) {
            // A malformed value becomes "no tenant" rather than an exception: throwing here would
            // surface as a 500 from a parameter a caller controls. The controller's own
            // null-handling then applies.
            return null;
        }
    }
}
