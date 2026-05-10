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

import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.appcompat.app.AlertDialog;

import com.alipay.hulu.common.application.LauncherApplication;
import com.alipay.hulu.common.constant.Constant;
import com.alipay.hulu.common.injector.InjectorService;
import com.alipay.hulu.common.injector.param.Subscriber;
import com.alipay.hulu.common.injector.provider.Param;
import com.alipay.hulu.common.utils.LogUtil;
import com.alipay.hulu.common.utils.StringUtil;
import com.alipay.hulu.shared.R;
import com.alipay.hulu.shared.display.items.base.DisplayItem;
import com.alipay.hulu.shared.display.items.base.Displayable;
import com.alipay.hulu.shared.display.items.base.RecordPattern;
import com.alipay.hulu.shared.display.items.util.FinalR;
import com.alipay.hulu.shared.display.items.util.GLFpsUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenGL FPS measurement tool using SurfaceFlinger latency data.
 *
 * <p>The user triggers a layer-selection dialog (via {@link #trigger()}) to pick
 * which OpenGL SurfaceView to monitor.  The tool then polls
 * {@code dumpsys SurfaceFlinger --latency} for that layer and computes:
 * <ul>
 *   <li>Frame rate (frames/s)</li>
 *   <li>Jank count and percentage</li>
 *   <li>Max frame time (ms)</li>
 *   <li>Stutter count and percentage</li>
 *   <li>Severe stutter count</li>
 *   <li>Dropped frame count</li>
 * </ul>
 */
@DisplayItem(nameRes = FinalR.GL_FPS, key = "glFps", permissions = {"adb"})
public class GLFpsTools implements Displayable {

    private static final String TAG = "GLFpsTools";

    // -----------------------------------------------------------------------
    // Static recording state
    // -----------------------------------------------------------------------

    private static long startTime = 0L;

    private static List<RecordPattern.RecordItem> fpsCurrent;
    private static List<RecordPattern.RecordItem> jankCountCurrent;
    private static List<RecordPattern.RecordItem> jankPercentCurrent;
    private static List<RecordPattern.RecordItem> maxFrameTimeCurrent;
    private static List<RecordPattern.RecordItem> stutterCountCurrent;
    private static List<RecordPattern.RecordItem> severeStutterCountCurrent;
    private static List<RecordPattern.RecordItem> stutterPercentCurrent;
    private static List<RecordPattern.RecordItem> dropFrameCountCurrent;

    // -----------------------------------------------------------------------
    // Instance state (injected by InjectorService)
    // -----------------------------------------------------------------------

    private InjectorService injectorService;

    /** Currently selected SurfaceView layer name. */
    private String currentLayerName = null;

    /** Index of the chosen SurfaceView layer (injected via {@code chosenSurfaceViewLayer}). */
    private int chosenLayerIndex = 0;

    /** {@code true} when {@link #currentLayerName} has been updated and not yet recorded. */
    private boolean layerChanged = false;

    /** Guard flag to prevent re-entrant {@link #trigger()} calls. */
    private boolean triggering = false;

    /** Target app package name (injected via {@code app}). */
    private String targetApp = "";

    // -----------------------------------------------------------------------
    // Displayable – lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void start() {
        injectorService = LauncherApplication.getInstance()
                .findServiceByName(InjectorService.class.getName());
        injectorService.register(this);
        GLFpsUtil.reset();
    }

    @Override
    public void stop() {
        injectorService.unregister(this);
        injectorService = null;
    }

    @Override
    public void clear() {
        fpsCurrent = null;
        jankCountCurrent = null;
        jankPercentCurrent = null;
        maxFrameTimeCurrent = null;
        stutterCountCurrent = null;
        severeStutterCountCurrent = null;
        stutterPercentCurrent = null;
        dropFrameCountCurrent = null;
    }

    // -----------------------------------------------------------------------
    // Displayable – recording
    // -----------------------------------------------------------------------

    @Override
    public void startRecord() {
        fpsCurrent = new ArrayList<>();
        jankCountCurrent = new ArrayList<>();
        jankPercentCurrent = new ArrayList<>();
        maxFrameTimeCurrent = new ArrayList<>();
        stutterCountCurrent = new ArrayList<>();
        severeStutterCountCurrent = new ArrayList<>();
        stutterPercentCurrent = new ArrayList<>();
        dropFrameCountCurrent = new ArrayList<>();
        startTime = System.currentTimeMillis();
    }

    @Override
    public void record() {
        Number[] metrics = fetchMetrics();
        if (metrics == null) return;

        // Determine the layer-change annotation for this sample
        String tag;
        if (layerChanged) {
            tag = currentLayerName;
            layerChanged = false;
        } else {
            tag = "";
        }

        long timestamp = metrics[8].longValue();
        fpsCurrent.add(new RecordPattern.RecordItem(timestamp, metrics[0].floatValue(), tag));
        jankCountCurrent.add(new RecordPattern.RecordItem(timestamp, metrics[1].floatValue(), tag));
        jankPercentCurrent.add(new RecordPattern.RecordItem(timestamp, metrics[2].floatValue(), tag));
        maxFrameTimeCurrent.add(new RecordPattern.RecordItem(timestamp, metrics[3].floatValue(), tag));
        stutterCountCurrent.add(new RecordPattern.RecordItem(timestamp, metrics[4].floatValue(), tag));
        severeStutterCountCurrent.add(new RecordPattern.RecordItem(timestamp, metrics[5].floatValue(), tag));
        stutterPercentCurrent.add(new RecordPattern.RecordItem(timestamp, metrics[6].floatValue(), tag));
        dropFrameCountCurrent.add(new RecordPattern.RecordItem(timestamp, metrics[7].floatValue(), tag));
    }

    @Override
    public Map<RecordPattern, List<RecordPattern.RecordItem>> stopRecord() {
        Map<RecordPattern, List<RecordPattern.RecordItem>> result = new HashMap<>();
        long endTime = System.currentTimeMillis();

        result.put(buildPattern(R.string.display_glfps__framerate, "frames/s"), fpsCurrent);
        result.put(buildPattern(R.string.display_glfps__jank_count, "times"), jankCountCurrent);
        result.put(buildPattern(R.string.display_glfps__jank_percent, "%"), jankPercentCurrent);
        result.put(buildPattern(R.string.display_glfps__max_frame_time, "ms"), maxFrameTimeCurrent);
        result.put(buildPattern(R.string.display_glfps__stutter_count, "times"), stutterCountCurrent);
        result.put(buildPattern(R.string.display_glfps__severe_stutter_count, "times"), severeStutterCountCurrent);
        result.put(buildPattern(R.string.display_glfps__stutter_percent, "%"), stutterPercentCurrent);
        result.put(buildPattern(R.string.display_glfps__skip_frame_count, "frames"), dropFrameCountCurrent);

        for (RecordPattern pattern : result.keySet()) {
            pattern.setStartTime(startTime);
            pattern.setEndTime(endTime);
        }

        clear();
        return result;
    }

    // -----------------------------------------------------------------------
    // Displayable – display
    // -----------------------------------------------------------------------

    @Override
    public String getCurrentInfo() throws Exception {
        Number[] metrics = fetchMetrics();
        return formatCurrentInfo(metrics);
    }

    @Override
    public long getRefreshFrequency() {
        return 900L;
    }

    // -----------------------------------------------------------------------
    // Displayable – trigger (layer selection dialog)
    // -----------------------------------------------------------------------

    @Override
    public void trigger() {
        if (triggering) return;
        triggering = true;

        String[] layers = GLFpsUtil.listSurfaceViewLayers(targetApp);
        if (layers.length > 0) {
            showLayerSelectionDialog(layers);
        } else {
            LauncherApplication.getInstance()
                    .showToast(StringUtil.getString(R.string.display_glfps__no_gl_view));
        }

        triggering = false;
    }

    // -----------------------------------------------------------------------
    // Injector subscribers
    // -----------------------------------------------------------------------

    /**
     * Receives the index of the user-chosen SurfaceView layer.
     * Published by the layer-selection dialog when the user taps OK.
     */
    @Subscriber(@Param("chosenSurfaceViewLayer"))
    public void setSurfaceViewLayer(int layerIndex) {
        LogUtil.i(TAG, "setSurfaceViewLayer: %s", layerIndex);
        this.chosenLayerIndex = layerIndex;
    }

    /**
     * Receives the current top-most SurfaceView layer name from {@link GLFpsUtil}.
     * Published automatically when the detected layer changes.
     */
    @Subscriber(@Param("currentSurfaceViewLayer"))
    public void setTopSurfaceViewLayer(String layerName) {
        this.currentLayerName = layerName;
        this.layerChanged = true;
    }

    /**
     * Receives the target app package name from the performance monitor framework.
     */
    @Subscriber(@Param("app"))
    public void setTargetApp(String app) {
        this.targetApp = (app == null) ? "" : app;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Fetches the GL FPS metrics for the currently selected layer.
     *
     * @return 9-element metrics array (see {@link GLFpsUtil#getMetrics}), or {@code null}
     */
    private Number[] fetchMetrics() {
        return GLFpsUtil.getMetrics(null, chosenLayerIndex, targetApp);
    }

    /**
     * Formats the metrics array into a short human-readable string for the HUD.
     */
    private String formatCurrentInfo(Number[] metrics) {
        if (metrics == null) {
            if (currentLayerName == null) {
                return StringUtil.getString(R.string.display_glfps__no_view);
            }
            return "-";
        }
        return String.format(Locale.US,
                StringUtil.getString(R.string.display_glfps__current_info),
                metrics[0].intValue());
    }

    /** Shows an AlertDialog letting the user choose which SurfaceView layer to monitor. */
    private void showLayerSelectionDialog(final String[] layers) {
        final Context context = LauncherApplication.getContext();
        View contentView = LayoutInflater.from(context)
                .inflate(R.layout.dialog_glfps_layer_choosen, null);
        RadioGroup radioGroup = contentView.findViewById(R.id.rg_layer);

        final int[] selectedIndex = {0};

        radioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                View btn = group.findViewById(checkedId);
                if (btn != null && btn.getTag() instanceof Integer) {
                    selectedIndex[0] = (Integer) btn.getTag();
                }
            }
        });

        // Build radio buttons for each layer
        RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT);

        for (int i = 0; i < layers.length; i++) {
            RadioButton button = new RadioButton(context);
            button.setPadding(10, 20, 0, 20);
            button.setTextSize(18f);
            button.setText(layers[i]);
            button.setTag(i);
            radioGroup.addView(button, params);

            if (StringUtil.equals(currentLayerName, layers[i])) {
                button.setChecked(true);
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(StringUtil.getString(R.string.display_glfps__select_layer))
                .setView(contentView)
                .setCancelable(false)
                .setPositiveButton(com.alipay.hulu.common.R.string.constant__confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        // Publish the chosen layer index so all subscribers (including this
                        // instance's setSurfaceViewLayer) are updated.
                        InjectorService service = InjectorService.g();
                        if (service != null) {
                            service.pushMessage("chosenSurfaceViewLayer", selectedIndex[0]);
                        }
                        currentLayerName = (selectedIndex[0] < layers.length)
                                ? layers[selectedIndex[0]] : null;
                        layerChanged = true;
                    }
                })
                .setNegativeButton(com.alipay.hulu.common.R.string.constant__cancel, null)
                .create();

        // Set overlay window type so the dialog appears above other apps
        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(Constant.TYPE_ALERT);
            dialog.getWindow().setLayout(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT);
        }

        dialog.show();
    }

    private RecordPattern buildPattern(int nameResId, String unit) {
        return new RecordPattern(StringUtil.getString(nameResId), unit, "glFps");
    }
}
