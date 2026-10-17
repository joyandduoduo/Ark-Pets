/** Copyright (c) 2022-2026, Harry Huang
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.animations;

import com.esotericsoftware.spine.AnimationState;


public class AnimComposer {
    protected final AnimationState state;
    protected final int coreTrackId = 0;
    protected AnimData playing;
    private boolean smoothChains;

    public void setSmoothChains(boolean enabled) { smoothChains = enabled; }

    public AnimComposer(AnimationState boundState) {
        AnimComposer composer = this;
        state = boundState;
        state.addListener(new AnimationState.AnimationStateAdapter() {
            @Override
            public void complete(AnimationState.TrackEntry entry) {
                if (composer.playing != null && entry.getAnimation() != null) {
                    if (entry.getAnimation().getName().equals(composer.playing.animClip().fullName)) {
                        AnimData completed = composer.playing;
                        if (!completed.isLoop()) {
                            if (smoothChains && completed.animNext() == null) return;
                            if (smoothChains && completed.animNext() != null) {
                                // Keeps the outgoing pose available for Spine's configured mixing.
                                composer.playing = null;
                                composer.offer(completed.animNext());
                                if (completed.name().equals(completed.animNext().name()))
                                    state.getCurrent(coreTrackId).setMixDuration(0);
                                return;
                            }
                            composer.reset();
                            if (completed.animNext() != null) {
                                composer.offer(completed.animNext());
                            }
                        }
                    }
                }
            }
        });
    }

    public boolean offer(AnimData animData) {
        if (animData != null) {
            if (playing == null || (!playing.isStrict() && !playing.equals(animData))) {
                playing = animData;
                state.setAnimation(coreTrackId, playing.name(), playing.isLoop());
                onApply(playing);
                return true;
            }
        }
        return false;
    }

    public AnimData getPlaying() {
        return playing;
    }

    /** Detects a finished finite chain while preserving its final pose. */
    public boolean isFinished() {
        AnimationState.TrackEntry entry = state.getCurrent(coreTrackId);
        return playing != null && !playing.isLoop() && playing.animNext() == null
                && entry != null && entry.isComplete();
    }

    public void reset() {
        playing = null;
        state.setEmptyAnimation(coreTrackId, 0f);
    }

    protected void onApply(AnimData playing) {
    }

    @Override
    public String toString() {
        return "AnimComposer {" + playing + '}';
    }
}
