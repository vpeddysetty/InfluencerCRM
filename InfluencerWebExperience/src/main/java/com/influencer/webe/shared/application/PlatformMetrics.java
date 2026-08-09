package com.influencer.webe.shared.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain counters for the decisions that matter operationally (roadmap Phase H).
 *
 * <p>The platform had no metrics at all. Rather than instrumenting everything, this counts the
 * handful of events where a silent change in rate is the first sign something is wrong:
 *
 * <ul>
 *   <li><b>Sanitizer rejections</b> — a jump means either an attack or a legitimate builder
 *       feature being stripped, and both need looking at.</li>
 *   <li><b>Vetting decisions by outcome</b> — a rule change that starts rejecting most
 *       applicants shows up here before a brand complains.</li>
 *   <li><b>Health alerts raised</b> — the alert-fatigue signal. A sudden spike means a
 *       threshold is wrong, not that every creator declined at once.</li>
 *   <li><b>Public page renders and expiries</b> — an expiry rate climbing is a revenue signal
 *       as much as an operational one.</li>
 *   <li><b>Stage transitions refused</b> — a rise means the transition map disagrees with how
 *       people actually work.</li>
 * </ul>
 *
 * <p>Counters are tagged rather than named per case, so a new outcome does not need a new meter.
 * They are deliberately NOT tagged by brand: tenant-cardinality tags are the standard way to
 * make a metrics backend fall over, and per-brand questions belong in the database where the
 * audit trails already answer them.
 */
@Component
public class PlatformMetrics {

    private final MeterRegistry registry;

    public PlatformMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A landing page render. `outcome` is served | expired | not_found. */
    public void pageRendered(String outcome) {
        counter("influencrm.landing.render", "outcome", outcome).increment();
    }

    /**
     * Builder HTML or CSS was filtered.
     *
     * <p>Worth watching in both directions: a spike suggests an attack, and a steady low rate
     * on legitimate pages suggests the allow-list is too narrow — which is exactly how the
     * missing div and h1 tags were found in Phase A.
     */
    public void sanitizerDropped(String kind) {
        counter("influencrm.sanitizer.dropped", "kind", kind).increment();
    }

    /** A vetting decision. `outcome` is rejected | under_review | approved; `by` is rule | human. */
    public void vettingDecision(String outcome, String by) {
        registry.counter("influencrm.vetting.decision", "outcome", outcome, "by", by).increment();
    }

    /** A creator health alert was raised. `type` is the alert type. */
    public void healthAlertRaised(String type) {
        counter("influencrm.creator.health_alert", "type", type).increment();
    }

    /** A page stage transition. `outcome` is accepted | refused. */
    public void stageTransition(String outcome) {
        counter("influencrm.landing.stage_transition", "outcome", outcome).increment();
    }

    /** An asset upload. `outcome` is accepted | rejected. */
    public void assetUpload(String outcome) {
        counter("influencrm.asset.upload", "outcome", outcome).increment();
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        // Null-safe tag value: a null tag throws inside Micrometer, and a metrics call must
        // never be the thing that fails a request.
        return registry.counter(name, tagKey, tagValue == null ? "unknown" : tagValue);
    }
}
