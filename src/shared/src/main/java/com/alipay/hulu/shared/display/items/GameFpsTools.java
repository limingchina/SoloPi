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
package com.alipay.hulu.shared.display.items;

import com.alipay.hulu.common.tools.CmdTools;
import com.alipay.hulu.common.utils.LogUtil;
import com.alipay.hulu.common.utils.StringUtil;
import com.alipay.hulu.shared.R;
import com.alipay.hulu.shared.display.items.base.DisplayItem;
import com.alipay.hulu.shared.display.items.base.Displayable;
import com.alipay.hulu.shared.display.items.base.RecordPattern;
import com.alipay.hulu.shared.display.items.util.FinalR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Game FPS measurement tool using SurfaceFlinger latency data.
 *
 * Uses {@code dumpsys SurfaceFlinger --latency} to measure frame timings for
 * the target app's rendering surface. Computes frame rate, jank count, max
 * jank time, and jank percentage over a rolling 1-second window.
 */
@DisplayItem(nameRes = FinalR.GAME_FPS, key = "GameFPS", permissions = {"adb"})
public class GameFpsTools implements Displayable {

    private static final String TAG = "GameFpsTools";

    private static long startTime;

    private static List<RecordPattern.RecordItem> fpsCurrent;
    private static List<RecordPattern.RecordItem> jankCurrent;
    private static List<RecordPattern.RecordItem> maxJankCurrent;
    private static List<RecordPattern.RecordItem> jankPercentCurrent;

    /** Target app package name used to filter SurfaceFlinger layer names. */
    private String targetApp = "";

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /**
     * Represents one frame's timing triple from {@code dumpsys SurfaceFlinger --latency}.
     * Each output row is: [desiredPresentTime] [actualPresentTime] [frameReadyTime]
     * (all in nanoseconds).
     */
    private static class FrameData {
        final long desiredPresentTime; // column 0 – when app queued the buffer
        final long actualPresentTime;  // column 1 – when frame appeared on screen
        final long frameReadyTime;     // column 2 – when GPU finished the frame

        FrameData(String[] parts) {
            desiredPresentTime = Long.parseLong(parts[0]);
            actualPresentTime  = Long.parseLong(parts[1]);
            frameReadyTime     = Long.parseLong(parts[2]);
        }
    }

    /** Parsed output of {@code dumpsys SurfaceFlinger --latency <layer>}. */
    private static class LatencyData {
        long refreshPeriod;        // nanoseconds per display refresh (line 0 of output)
        List<FrameData> frames;
    }

    // -------------------------------------------------------------------------
    // Displayable interface
    // -------------------------------------------------------------------------

    /** Sets the target app package name used to identify its SurfaceFlinger layer. */
    public void setTargetApp(String targetApp) {
        this.targetApp = (targetApp == null) ? "" : targetApp;
    }

    @Override
    public void start() {
        // No background threads or services required; commands run on-demand.
    }

    @Override
    public void stop() {
        // Nothing to tear down.
    }

    @Override
    public void clear() {
        fpsCurrent = null;
        jankCurrent = null;
        maxJankCurrent = null;
        jankPercentCurrent = null;
    }

    @Override
    public void startRecord() {
        fpsCurrent = new ArrayList<>();
        jankCurrent = new ArrayList<>();
        maxJankCurrent = new ArrayList<>();
        jankPercentCurrent = new ArrayList<>();
        startTime = System.currentTimeMillis();
    }

    @Override
    public void record() {
        LayerQueryResult query = queryLayer();
        if (query == null) return;

        LatencyData data = parseLatencyData(query.layerName);
        if (data == null || data.frames == null || data.frames.size() <= 1) {
            if (!query.hasSurfaceView) return;
            data = parseLatencyData("SurfaceView");
        }

        Number[] metrics = computeMetrics(data);
        if (metrics == null) return;

        // metrics[0] = frameCount, [1] = jankCount, [2] = maxJankTimeNs, [3] = jankPercent
        fpsCurrent.add(new RecordPattern.RecordItem(
                System.currentTimeMillis(), metrics[0].floatValue(), null));

        jankCurrent.add(new RecordPattern.RecordItem(
                System.currentTimeMillis(), metrics[1].floatValue(), null));

        float maxJankMs = (float) (metrics[2].longValue() / 1_000_000.0);
        maxJankCurrent.add(new RecordPattern.RecordItem(
                System.currentTimeMillis(), maxJankMs, null));

        jankPercentCurrent.add(new RecordPattern.RecordItem(
                System.currentTimeMillis(), metrics[3].floatValue(), null));
    }

    @Override
    public String getCurrentInfo() throws Exception {
        LayerQueryResult query = queryLayer();
        if (query == null) return "-";

        LatencyData data = parseLatencyData(query.layerName);
        if (data == null || data.frames == null || data.frames.size() <= 1) {
            if (!query.hasSurfaceView) return "-";
            data = parseLatencyData("SurfaceView");
        }

        return formatMetrics(computeMetrics(data));
    }

    @Override
    public long getRefreshFrequency() {
        return 900L; // milliseconds between polls
    }

    @Override
    public void trigger() {
        // No trigger action needed.
    }

    @Override
    public Map<RecordPattern, List<RecordPattern.RecordItem>> stopRecord() {
        Map<RecordPattern, List<RecordPattern.RecordItem>> result = new HashMap<>();
        Long endTime = System.currentTimeMillis();

        RecordPattern pattern = new RecordPattern(
                StringUtil.getString(R.string.display_fps__framerate),
                StringUtil.getString(R.string.display_fps__frame),
                "GameFPS");
        pattern.setStartTime(startTime);
        pattern.setEndTime(endTime);
        result.put(pattern, fpsCurrent);

        pattern = new RecordPattern(
                StringUtil.getString(R.string.display_fps__jank_time),
                StringUtil.getString(R.string.display_fps__count),
                "GameFPS");
        pattern.setStartTime(startTime);
        pattern.setEndTime(endTime);
        result.put(pattern, jankCurrent);

        pattern = new RecordPattern(
                StringUtil.getString(R.string.display_fps__max_jank_time),
                "ms", "GameFPS");
        pattern.setStartTime(startTime);
        pattern.setEndTime(endTime);
        result.put(pattern, maxJankCurrent);

        pattern = new RecordPattern(
                StringUtil.getString(R.string.display_fps__jank_percentage),
                "%", "GameFPS");
        pattern.setStartTime(startTime);
        pattern.setEndTime(endTime);
        result.put(pattern, jankPercentCurrent);

        fpsCurrent = null;
        jankCurrent = null;
        maxJankCurrent = null;
        jankPercentCurrent = null;

        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Holds the result of querying SurfaceFlinger for the target layer. */
    private static class LayerQueryResult {
        String layerName;
        boolean hasSurfaceView; // true when an exact "SurfaceView" layer exists
    }

    /**
     * Runs {@code dumpsys SurfaceFlinger --list} and selects the best layer
     * name for the configured target app.
     *
     * <p>Selection rules:
     * <ol>
     *   <li>Collect all layer names containing {@link #targetApp}.</li>
     *   <li>Among those, prefer the last one whose name starts with
     *       {@code "SurfaceView"} (typical for OpenGL/game rendering).</li>
     *   <li>If none start with {@code "SurfaceView"}, use the last matching
     *       layer.</li>
     *   <li>If no layers contain the target app but an exact {@code "SurfaceView"}
     *       layer exists, use that as fallback.</li>
     *   <li>Otherwise return {@code null}.</li>
     * </ol>
     */
    private LayerQueryResult queryLayer() {
        String output = CmdTools.execHighPrivilegeCmd("dumpsys SurfaceFlinger --list");
        if (StringUtil.isEmpty(output)) return null;

        String[] lines = output.split("\n");
        List<String> matchingLines = new ArrayList<>();
        boolean hasSurfaceView = false;

        for (String line : lines) {
            if (StringUtil.isEmpty(line)) continue;
            if (line.contains(targetApp)) {
                matchingLines.add(line);
            }
            if (line.trim().equals("SurfaceView")) {
                hasSurfaceView = true;
            }
        }

        String selectedLayer = null;
        if (!matchingLines.isEmpty()) {
            // Default: last matching layer
            selectedLayer = matchingLines.get(matchingLines.size() - 1).trim();
            // Prefer the last layer whose name starts with "SurfaceView"
            for (int i = matchingLines.size() - 1; i >= 0; i--) {
                String trimmed = matchingLines.get(i).trim();
                if (trimmed.startsWith("SurfaceView")) {
                    selectedLayer = trimmed;
                    break;
                }
            }
        } else if (hasSurfaceView) {
            selectedLayer = "SurfaceView";
        }

        if (selectedLayer == null) return null;

        LayerQueryResult result = new LayerQueryResult();
        result.layerName = selectedLayer;
        result.hasSurfaceView = hasSurfaceView;
        return result;
    }

    /**
     * Runs {@code dumpsys SurfaceFlinger --latency "<layerName>"} and parses
     * the output into a {@link LatencyData} object.
     *
     * <p>Invalid frames (actualPresentTime == 0, or with a timestamp jump
     * larger than 100× the first frame's timestamp) are discarded.
     */
    private static LatencyData parseLatencyData(String layerName) {
        String output = CmdTools.execHighPrivilegeCmd(
                "dumpsys SurfaceFlinger --latency \"" + layerName + "\"");
        if (StringUtil.isEmpty(output)) return null;

        String[] lines = output.trim().split("\n");
        LatencyData result = new LatencyData();
        List<FrameData> frames = new ArrayList<>(lines.length + 1);

        LogUtil.i(TAG, "Get Refresh rate: [%s]", lines[0]);
        result.refreshPeriod = Long.parseLong(lines[0].trim());

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (StringUtil.isEmpty(line)) continue;

            String[] parts = line.split("\\s+");
            if (parts.length != 3) continue;

            // Validate the first field before attempting to construct FrameData
            if (!isValidTimestamp(parts[0].trim())) continue;

            FrameData frame;
            try {
                frame = new FrameData(parts);
            } catch (NumberFormatException e) {
                continue;
            }

            // Skip frames that were never presented
            if (frame.actualPresentTime == 0) continue;

            // Skip frames whose timestamp is wildly ahead of the first frame
            // (indicates a SurfaceFlinger buffer reset or counter wrap-around)
            if (!frames.isEmpty()) {
                long firstActual = frames.get(0).actualPresentTime;
                if (frame.actualPresentTime >= firstActual * 100) continue;
            }

            frames.add(frame);
        }

        result.frames = frames;
        return result;
    }

    /** Returns {@code true} if {@code s} can be parsed as a {@code long}. */
    private static boolean isValidTimestamp(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Long.parseLong(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Computes FPS metrics from a rolling 1-second window ending at the last frame.
     *
     * <p>A frame is considered a <em>jank</em> if its render duration
     * (frameReadyTime − desiredPresentTime) exceeds the display refresh period.
     *
     * @return {@code Number[4]}: [frameCount, jankCount, maxJankTimeNs, jankPercent],
     *         or {@code null} if insufficient data.
     */
    private static Number[] computeMetrics(LatencyData data) {
        if (data == null) return null;

        List<FrameData> frames = data.frames;
        if (frames == null || frames.size() <= 1) return null;

        long refreshPeriod = data.refreshPeriod;

        // Anchor: the last frame defines the window end and initial jank state
        FrameData lastFrame = frames.get(frames.size() - 1);
        long lastDuration = lastFrame.frameReadyTime - lastFrame.desiredPresentTime;
        int jankCount = (lastDuration > refreshPeriod) ? 1 : 0;
        long maxJankTime = lastDuration;

        // Cutoff: only consider frames rendered within the last second (in ns)
        long cutoff = lastFrame.actualPresentTime - 1_000_000_000L;
        int frameCount = 1;

        // Walk backward through frames that fall within the 1-second window
        for (int i = frames.size() - 2; i >= 0; i--) {
            FrameData frame = frames.get(i);
            if (frame.actualPresentTime < cutoff) break;

            frameCount++;
            long duration = frame.frameReadyTime - frame.desiredPresentTime;
            if (duration > maxJankTime) maxJankTime = duration;
            if (duration > refreshPeriod) jankCount++;
        }

        double jankPercent = (frameCount > 0) ? (double) jankCount / frameCount : 0.0;
        return new Number[]{frameCount, jankCount, maxJankTime, jankPercent};
    }

    /**
     * Formats computed metrics as a human-readable string.
     *
     * @param metrics the array returned by {@link #computeMetrics}, or {@code null}
     * @return formatted string, or {@code "-"} when metrics are unavailable
     */
    private static String formatMetrics(Number[] metrics) {
        if (metrics == null) return "-";
        int maxJankMs = (int) (metrics[2].longValue() / 1_000_000.0);
        return StringUtil.getString(R.string.display_gamefps__current_info,
                metrics[0], metrics[1], maxJankMs, metrics[3]);
    }
}
