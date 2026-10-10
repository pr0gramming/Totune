package com.totune.app.dsp;

/** Progress callback, 0..1. */
public interface Progress {
    void update(float fraction);
}
