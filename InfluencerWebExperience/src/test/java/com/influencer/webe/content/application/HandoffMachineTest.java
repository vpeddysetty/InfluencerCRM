package com.influencer.webe.content.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose move it is (roadmap PR-40).
 *
 * <p>The property under test throughout is that <b>turn and stage are independent</b>. Most of
 * these assertions look almost trivial in isolation; together they pin the one decision that would
 * be undone by a well-meaning simplification — deriving the turn from the stage, which works right
 * up until a page bounces back and forth without its stage moving.
 */
class HandoffMachineTest {

    private final HandoffMachine machine = new HandoffMachine();

    @Test
    @DisplayName("a stage change does not move the turn")
    void stageChangesLeaveTheTurnAlone() {
        // The core of the design. A page at content_needed with the creator holding it stays with
        // the creator when the stage moves for some unrelated reason -- reviewing a page does not
        // take it off the person editing it.
        assertThat(machine.turnAfterStage(LandingStageMachine.CONTENT_NEEDED, HandoffMachine.CREATOR))
                .isEqualTo(HandoffMachine.CREATOR);
        assertThat(machine.turnAfterStage(LandingStageMachine.READY_TO_PUBLISH, HandoffMachine.BRAND))
                .isEqualTo(HandoffMachine.BRAND);
        assertThat(machine.turnAfterStage(LandingStageMachine.APPROVED, HandoffMachine.CREATOR))
                .isEqualTo(HandoffMachine.CREATOR);
    }

    @Test
    @DisplayName("publishing clears the turn, because nobody owes anything after it")
    void publishingClearsTheTurn() {
        // The one exception, and the reason it exists: leaving a turn set on a live page keeps it
        // in somebody's "waiting on you" list forever, which trains people to ignore that list.
        assertThat(machine.turnAfterStage(LandingStageMachine.PUBLISHED, HandoffMachine.CREATOR))
                .isNull();
        assertThat(machine.turnAfterStage(LandingStageMachine.PERFORMANCE_TRACKING, HandoffMachine.BRAND))
                .isNull();
    }

    @Test
    @DisplayName("a page can only be handed off from a stage where that makes sense")
    void handoffIsRestrictedToCollaborationStages() {
        assertThat(machine.canHandOff(LandingStageMachine.APPROVED)).isTrue();
        assertThat(machine.canHandOff(LandingStageMachine.CREATOR_ASSIGNED)).isTrue();
        assertThat(machine.canHandOff(LandingStageMachine.CONTENT_NEEDED)).isTrue();

        // Handing off a first draft or a live page is a mis-click, not a workflow.
        assertThat(machine.canHandOff(LandingStageMachine.DRAFT)).isFalse();
        assertThat(machine.canHandOff(LandingStageMachine.PUBLISHED)).isFalse();
        assertThat(machine.canHandOff(null)).isFalse();
    }

    @Test
    @DisplayName("a creator can only hand back a page that is actually theirs")
    void handBackRequiresItToBeTheirTurn() {
        // Without this a creator could "return" a page they were never given, or return one twice
        // -- the second click landing after the brand had picked it up, silently taking it back
        // off them.
        assertThat(machine.canHandBack(HandoffMachine.CREATOR)).isTrue();
        assertThat(machine.canHandBack(HandoffMachine.BRAND)).isFalse();
        assertThat(machine.canHandBack(null)).isFalse();
    }

    @Test
    @DisplayName("the brand can always take a page back, even when the turn already says brand")
    void takeBackIsAlwaysAllowed() {
        // Deliberately unconditional. "Take it back" is how a brand recovers from an accidental
        // handoff or an unresponsive creator, and refusing it because the column already reads
        // `brand` would block exactly the case where the two have drifted apart.
        assertThat(machine.canTakeBack(HandoffMachine.CREATOR)).isTrue();
        assertThat(machine.canTakeBack(HandoffMachine.BRAND)).isTrue();
        assertThat(machine.canTakeBack(null)).isTrue();
    }

    @Test
    @DisplayName("only brand and creator are turns; null is a state, not a value")
    void turnVocabularyIsClosed() {
        assertThat(machine.isTurn(HandoffMachine.BRAND)).isTrue();
        assertThat(machine.isTurn(HandoffMachine.CREATOR)).isTrue();
        assertThat(machine.isTurn("BRAND")).isTrue();          // normalised
        assertThat(machine.isTurn(" creator ")).isTrue();      // normalised

        assertThat(machine.isTurn("agency")).isFalse();
        assertThat(machine.isTurn("")).isFalse();
        // null is nobody's turn -- a real state, and deliberately not a member of the vocabulary,
        // so a caller must handle it rather than passing it through a validity check.
        assertThat(machine.isTurn(null)).isFalse();
    }
}
