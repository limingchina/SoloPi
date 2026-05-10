/*
 * Copyright (C) 2015-present, Ant Financial Services Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.hulu.shared.display.items.util;

import com.alipay.hulu.common.injector.InjectorService;
import com.alipay.hulu.common.tools.CmdTools;
import com.alipay.hulu.common.utils.LogUtil;
import com.alipay.hulu.common.utils.StringUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility class for measuring OpenGL FPS via {@code dumpsys SurfaceFlinger --latency}.
 *
 * <p>The main entry point is {@link #getMetrics(String, int, String)}, which returns a
 * {@code Number[9]} array:
 * <ol start="0">
 *   <li>Frame count (Integer)</li>
 *   <li>Jank count – frames whose render duration exceeds the display refresh period (Integer)</li>
 *   <li>Jank percentage % (Float)</li>
 *   <li>Max frame time in ms (Float)</li>
 *   <li>Stutter count (Integer)</li>
 *   <li>Severe stutter count (Integer)</li>
 *   <li>Stutter percentage % (Float)</li>
 *   <li>Dropped frame count (Integer)</li>
 *   <li>Observation timestamp – ms since epoch when the sample was taken (Long)</li>
 * </ol>
 */
public class GLFpsUtil {

    private static final String TAG = "GLFpsUtil";

    /** Minimum number of latency output lines required for a valid sample. */
    private static final int MIN_LINES = 64;

    // -----------------------------------------------------------------------
    // Layer selection cache
    // -----------------------------------------------------------------------

    /** Cached selected layer name. */
    private static String cachedLayerName = null;
    /** Cached previous layer name (for change-detection). */
    private static String previousLayerName = null;
    /** App package whose layers were last queried. */
    private static String cachedApp = null;
    /** Layer index for which {@link #cachedLayerName} was selected. */
    private static int cachedLayerIndex = 0;
    /** The last line seen in a latency dump – used to detect new data. */
    private static String lastSeenLine = null;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns GL FPS metrics for the given layer / target app.
     *
     * @param layerName  explicit layer name, or {@code null} to select automatically
     * @param layerIndex preferred layer index (used when {@code layerName} is null)
     * @param targetApp  target app package name (used when {@code layerName} is null)
     * @return 9-element metrics array, or {@code null} on error
     */
    public static Number[] getMetrics(String layerName, int layerIndex, String targetApp) {
        LatencyBuffer buf = readLatency(layerName, layerIndex, targetApp);
        return computeMetrics(buf);
    }

    /**
     * Lists all SurfaceView layer names for {@code targetApp} that are currently
     * visible to SurfaceFlinger.
     *
     * @param targetApp package name to filter by
     * @return array of unique SurfaceView layer names (may be empty)
     */
    public static String[] listSurfaceViewLayers(String targetApp) {
        String cmd = String.format(
                "dumpsys SurfaceFlinger --list | grep SurfaceView | grep -v Background | grep %s",
                targetApp);
        String output = CmdTools.execHighPrivilegeCmd(cmd);
        LogUtil.d(TAG, "listSurfaceViewLayers result: %s", output);

        Set<String> result = new LinkedHashSet<>();
        if (!StringUtil.isEmpty(output) && output.contains("SurfaceView")) {
            boolean hasBlast = output.contains("(BLAST)");
            String[] lines = output.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (hasBlast && !line.contains("(BLAST)")) {
                    // When BLAST is present, only process BLAST layer lines (skip plain ones)
                    continue;
                }

                int svIdx = line.indexOf("SurfaceView");
                if (svIdx < 0) continue;

                // Extract layer name: handle "SurfaceView[...]" or "23a410c SurfaceView[...]"
                // often found in "RequestedLayerState{...}" or similar verbose formats.
                int startIdx = 0;
                int openBraceIdx = line.lastIndexOf('{', svIdx);
                if (openBraceIdx >= 0) {
                    startIdx = openBraceIdx + 1;
                }

                // Find the end: usually before " key=" or at '}'
                int endIdx = line.length();
                int closeBraceIdx = line.indexOf('}', svIdx);
                if (closeBraceIdx >= 0) {
                    endIdx = closeBraceIdx;
                }

                // Check for trailing properties like " parentId="
                int searchStart = svIdx;
                while (true) {
                    int nextSpace = line.indexOf(' ', searchStart);
                    if (nextSpace < 0 || nextSpace >= endIdx) break;

                    // If space is followed by something like "parentId=", it's the end
                    int nextEqual = line.indexOf('=', nextSpace);
                    if (nextEqual > nextSpace && nextEqual < line.length()) {
                        endIdx = nextSpace;
                        break;
                    }
                    searchStart = nextSpace + 1;
                }

                String extracted = line.substring(startIdx, endIdx).trim();
                if (!extracted.isEmpty()) {
                    result.add(extracted);
                }
            }
        }
        return result.toArray(new String[0]);
    }

    /** Resets all cached state (call on session reset). */
    public static void reset() {
        cachedLayerName = null;
        previousLayerName = null;
        cachedApp = null;
        cachedLayerIndex = 0;
        lastSeenLine = null;
    }

    // -----------------------------------------------------------------------
    // Private helpers – layer resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves the layer name and runs {@code dumpsys SurfaceFlinger --latency} on it.
     *
     * <p>If {@code layerName} is non-null it is used directly; otherwise the cache is
     * consulted, falling back to {@link #listSurfaceViewLayers}.
     */
    private static LatencyBuffer readLatency(String layerName, int layerIndex, String targetApp) {
        String resolved = (layerName != null) ? layerName
                : resolveLayerName(targetApp, layerIndex);
        if (resolved == null || resolved.isEmpty()) return null;

        String cmd = "dumpsys SurfaceFlinger --latency \"" + resolved + "\"";
        String output = CmdTools.execHighPrivilegeCmd(cmd);
        return parseLatencyOutput(output);
    }

    /**
     * Selects a layer name using the cached value when possible, otherwise queries
     * SurfaceFlinger and caches the result.  Broadcasts a {@code currentSurfaceViewLayer}
     * event when the selection changes.
     *
     * <p>Selection logic:
     * <ol>
     *   <li>If {@code cachedLayerName} is non-null AND both {@code layerIndex} and
     *       {@code targetApp} match the cached values → return the cached name immediately
     *       (no re-query, no broadcast).</li>
     *   <li>Otherwise invalidate the cache, re-query via {@link #listSurfaceViewLayers},
     *       select by index (falling back to index 0), then broadcast
     *       {@code currentSurfaceViewLayer} only if the name changed.</li>
     * </ol>
     */
    private static String resolveLayerName(String targetApp, int layerIndex) {
        if (cachedLayerName != null) {
            if (layerIndex == cachedLayerIndex && StringUtil.equals(targetApp, cachedApp)) {
                // Cache hit: return immediately, no re-query, no broadcast
                return cachedLayerName;
            }
            // Index or app changed → invalidate
            cachedLayerName = null;
        }

        // Re-query
        cachedApp = targetApp;
        cachedLayerIndex = layerIndex;

        String[] layers = listSurfaceViewLayers(targetApp);
        if (layers.length == 0) return null;

        String selected = (layerIndex < layers.length) ? layers[layerIndex] : layers[0];
        cachedLayerName = selected;

        // Broadcast only when the resolved layer name has changed
        maybePublishLayerChange();
        return cachedLayerName;
    }

    /** Broadcasts {@code currentSurfaceViewLayer} when the layer selection changes. */
    private static void maybePublishLayerChange() {
        if (!StringUtil.equals(previousLayerName, cachedLayerName)) {
            InjectorService service = InjectorService.g();
            if (service != null) {
                service.pushMessage("currentSurfaceViewLayer", cachedLayerName);
            }
            previousLayerName = cachedLayerName;
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers – parsing
    // -----------------------------------------------------------------------

    /**
     * Parses the raw text output of {@code dumpsys SurfaceFlinger --latency}.
     *
     * <p>Line 0 is the refresh period in nanoseconds.  Subsequent lines are
     * space-separated triples: [desiredPresentTime actualPresentTime frameReadyTime].
     * Only frames inside a 1-second window ending at the last frame are kept; frames
     * whose {@code actualPresentTime} is 0 or wildly larger than the first frame are
     * discarded.
     *
     * <p>Requires at least {@value MIN_LINES} non-empty lines to be considered valid.
     */
    private static LatencyBuffer parseLatencyOutput(String output) {
        if (StringUtil.isEmpty(output)) return null;

        String[] lines = output.trim().split("\n");
        // Guard: need enough data
        if (lines.length < MIN_LINES) {
            LogUtil.w(TAG, "Latency output has only %d lines (need %d)", lines.length, MIN_LINES);
            return null;
        }

        // Determine the last valid (non-empty) line
        int lastIdx = lines.length - 1;
        if (StringUtil.isEmpty(lines[lastIdx])) {
            lastIdx--;
        }
        String lastLine = lines[lastIdx];

        // If we have already processed up to this line, return an empty-frames buffer
        if (StringUtil.equals(lastLine, lastSeenLine)) {
            long refreshPeriod = parseRefreshPeriod(lines[0]);
            return new LatencyBuffer(refreshPeriod, System.currentTimeMillis(),
                    new ArrayList<FrameData>());
        }
        lastSeenLine = lastLine;

        long refreshPeriod = parseRefreshPeriod(lines[0]);
        long nowMs = System.currentTimeMillis();

        List<FrameData> frames = new ArrayList<>(lastIdx);
        for (int i = 1; i <= lastIdx; i++) {
            String line = lines[i];
            if (StringUtil.isEmpty(line)) continue;

            String[] parts = line.trim().split("\\s+");
            if (parts.length != 3) continue;

            FrameData fd;
            try {
                fd = new FrameData(parts);
            } catch (NumberFormatException e) {
                continue;
            }
            if (fd.actualPresentTime == 0) continue;

            // Discard frames wildly ahead of the first frame (counter wrap-around)
            if (!frames.isEmpty()) {
                long first = frames.get(0).actualPresentTime;
                if (first != 0 && fd.actualPresentTime >= first * 100) continue;
            }

            frames.add(fd);
        }

        return new LatencyBuffer(refreshPeriod, nowMs, frames);
    }

    private static long parseRefreshPeriod(String line) {
        try {
            return Long.parseLong(line.trim());
        } catch (NumberFormatException e) {
            return 16_666_667L; // default ~60 Hz
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers – metrics computation
    // -----------------------------------------------------------------------

    /**
     * Computes the 9-element metrics array from a parsed latency buffer.
     *
     * <p>The algorithm logic:
     * <ul>
     *   <li>Walks backwards through frames within a 1-second window.</li>
     *   <li>A frame is a <em>jank</em> if its render duration
     *       ({@code frameReadyTime − desiredPresentTime}) exceeds the refresh period.</li>
     *   <li>A frame is a <em>stutter</em> if its duration exceeds both 2× the rolling
     *       3-frame average <em>and</em> 3× the refresh period, or simply 5× the refresh
     *       period.</li>
     *   <li>A stutter is <em>severe</em> when it also exceeds 5× (non-severe threshold)
     *       or 7× (severe threshold) the refresh period.</li>
     * </ul>
     */
    private static Number[] computeMetrics(LatencyBuffer buf) {
        if (buf == null) return null;

        List<FrameData> frames = buf.frames;
        if (frames == null || frames.size() <= 1) {
            // Return all-zeros with timestamp
            return zeroMetrics(buf == null ? 0L : buf.observationTimeMs);
        }

        long refreshPeriod = buf.refreshPeriod;
        int size = frames.size();
        FrameData lastFrame = frames.get(size - 1);

        // Duration of the last frame
        long lastDuration = lastFrame.frameReadyTime - lastFrame.desiredPresentTime;

        // 1-second cutoff in nanoseconds
        long cutoff = lastFrame.actualPresentTime - 1_000_000_000L;

        // Rolling 3-frame duration window for stutter detection
        List<Long> durationWindow = new ArrayList<>(3);

        int frameCount = 0;
        int jankCount = 0;
        int skipFrameCount = 0;
        int stutterCount = 0;
        int severeStutterCount = 0;
        long maxDuration = 0L;
        long totalDuration = 0L;
        long stutterTotalDuration = 0L;

        // Walk backward from the last frame through the 1-second window
        for (int i = size - 1; i >= 0; i--) {
            FrameData frame = frames.get(i);
            if (frame.actualPresentTime < cutoff) break;

            frameCount++;
            long duration = frame.frameReadyTime - frame.desiredPresentTime;
            totalDuration += duration;
            if (duration > maxDuration) maxDuration = duration;

            // Jank detection
            if (duration > refreshPeriod) {
                jankCount++;
                skipFrameCount += (int) (duration / refreshPeriod);
            }

            // Stutter detection using sliding 3-frame window
            if (durationWindow.size() < 3) {
                durationWindow.add(duration);
            } else {
                long avgDuration = (durationWindow.get(0) + durationWindow.get(1)
                        + durationWindow.get(2)) / 3;
                boolean isStutter = (duration > 2 * avgDuration && duration > 3 * refreshPeriod)
                        || (duration > 5 * refreshPeriod);
                if (isStutter) {
                    stutterCount++;
                    stutterTotalDuration += duration;
                    // Severe: exceeds 5× or 7× depending on whether the 2×-avg condition holds
                    long severeThreshold = (duration > 2 * avgDuration && duration > 3 * refreshPeriod)
                            ? 5 * refreshPeriod : 7 * refreshPeriod;
                    if (duration > severeThreshold) {
                        severeStutterCount++;
                    }
                }
                // Slide the window
                durationWindow.remove(0);
                durationWindow.add(duration);
            }
        }

        float jankPercent = (frameCount > 0) ? (jankCount * 100f / frameCount) : 0f;
        float stutterPercent = (totalDuration > 0)
                ? (stutterTotalDuration * 100f / totalDuration) : 0f;
        float maxFrameMs = maxDuration / 1_000_000f;

        return new Number[]{
                frameCount,           // [0] frame count
                jankCount,            // [1] jank count
                jankPercent,          // [2] jank %
                maxFrameMs,           // [3] max frame time ms
                stutterCount,         // [4] stutter count
                severeStutterCount,   // [5] severe stutter count
                stutterPercent,       // [6] stutter %
                skipFrameCount,       // [7] dropped frames
                buf.observationTimeMs // [8] timestamp
        };
    }

    private static Number[] zeroMetrics(long timestampMs) {
        return new Number[]{0, 0, 0f, 0f, 0, 0, 0f, 0, timestampMs};
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /** A single frame timing triple from the SurfaceFlinger latency dump. */
    static class FrameData {
        final long desiredPresentTime; // column 0 – when app queued the buffer (ns)
        final long actualPresentTime;  // column 1 – when frame appeared on screen (ns)
        final long frameReadyTime;     // column 2 – when GPU finished the frame (ns)

        FrameData(String[] parts) {
            desiredPresentTime = Long.parseLong(parts[0]);
            actualPresentTime  = Long.parseLong(parts[1]);
            frameReadyTime     = Long.parseLong(parts[2]);
        }
    }

    /** Holds a parsed latency dump ready for metric computation. */
    static class LatencyBuffer {
        final long refreshPeriod;       // nanoseconds per display refresh
        final long observationTimeMs;   // wall-clock time when sample was taken (ms)
        final List<FrameData> frames;

        LatencyBuffer(long refreshPeriod, long observationTimeMs, List<FrameData> frames) {
            this.refreshPeriod = refreshPeriod;
            this.observationTimeMs = observationTimeMs;
            this.frames = frames;
        }
    }
}
