/*
 * Copyright (C) 2013 salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.auraframework.test.perf.rdp;

import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Analyzes raw JavaScript CPU profiler data
 */
// see: http://src.chromium.org/viewvc/blink/trunk/Source/devtools/front_end/sdk/CPUProfileModel.js
// see: http://src.chromium.org/viewvc/blink/trunk/Source/devtools/front_end/profiler/CPUProfileFlameChart.js
public final class CPUProfilerAnalyzer {

    private final Map<String, ?> profile; // the raw input profile data
    private final Map<String, CPUProfileInfo> functionToInfo = Maps.newHashMap();
    private final Map<Number, Map<String, ?>> idToNode = Maps.newHashMap();
    private final double elapsedSeconds;
    private final int numSamples;
    private final long samplingIntervalMicros; // about 1ms
    private int depth = -1;
    private int maxDepth;

    @SuppressWarnings("unchecked")
    public CPUProfilerAnalyzer(Map<String, ?> profile) {
        this.profile = profile;

        double profilingStartTimeSeconds = (Double) profile.get("startTime");
        double profilingEndTimeSeconds = (Double) profile.get("endTime");
        elapsedSeconds = profilingEndTimeSeconds - profilingStartTimeSeconds;

        List<Integer> samples = (List<Integer>) profile.get("samples");
        numSamples = samples.size();
        samplingIntervalMicros = Math.round((elapsedSeconds * 1000000) / numSamples);
    }

    @SuppressWarnings("unchecked")
    public JSONObject analyze() throws JSONException {
        // format from: http://src.chromium.org/viewvc/blink/trunk/Source/devtools/protocol.json:
        // {
        // "id": "CPUProfileNode",
        // "type": "object",
        // "description": "CPU Profile node. Holds callsite information, execution statistics and child nodes.",
        // "properties": [
        // { "name": "functionName", "type": "string", "description": "Function name." },
        // { "name": "scriptId", "$ref": "Debugger.ScriptId", "description": "Script identifier." },
        // { "name": "url", "type": "string", "description": "URL." },
        // { "name": "lineNumber", "type": "integer", "description":
        // "1-based line number of the function start position." },
        // { "name": "columnNumber", "type": "integer", "description":
        // "1-based column number of the function start position." },
        // { "name": "hitCount", "type": "integer", "description":
        // "Number of samples where this node was on top of the call stack." },
        // { "name": "callUID", "type": "number", "description": "Call UID." },
        // { "name": "children", "type": "array", "items": { "$ref": "CPUProfileNode" }, "description": "Child nodes."
        // },
        // { "name": "deoptReason", "type": "string", "description":
        // "The reason of being not optimized. The function may be deoptimized or marked as don't optimize."},
        // { "name": "id", "type": "integer", "description": "Unique id of the node." }
        // ]
        // },
        // {
        // "id": "CPUProfile",
        // "type": "object",
        // "description": "Profile.",
        // "properties": [
        // { "name": "head", "$ref": "CPUProfileNode" },
        // { "name": "startTime", "type": "number", "description": "Profiling start time in seconds." },
        // { "name": "endTime", "type": "number", "description": "Profiling end time in seconds." },
        // { "name": "samples", "optional": true, "type": "array", "items": { "type": "integer" }, "description":
        // "Ids of samples top nodes." },
        // { "name": "timestamps", "optional": true, "type": "array", "items": { "type": "number" }, "description":
        // "Timestamps of the samples in microseconds." }
        // ]
        // }
        // System.out.println("profileData: " + new JSONObject(profile).toString(2));

        // traverse nodes and calculate cummulative total/self time for functions
        Map<String, ?> head = (Map<String, ?>) profile.get("head");
        int totalHitCount = traverseCPUProfileNodes(head);
        if (totalHitCount != numSamples) {
            throw new RuntimeException("miss match: " + numSamples + " != " + totalHitCount);
        }

        // find islands of usage: streches of non-(idle) samples
        int numIslands = 0;
        boolean inIsland = false;
        for (Number id : (List<Number>) profile.get("samples")) {
            Map<String, ?> node = idToNode.get(id);
            String functionName = (String) node.get("functionName");
            boolean inLand = !"(idle)".equals(functionName);
            if (inIsland != inLand) {
                if (inLand) {
                    numIslands++;
                }
                inIsland = inLand;
            }
        }

        // total # calls
        // TODO: average call depth in non-idle/...
        // total # calls in biggest island

        // return relevant metrics:
        JSONObject metrics = new JSONObject();
        try {
            metrics.put("elapsedMillis", Math.round(elapsedSeconds * 1000));
            metrics.put("numSamples", numSamples);
            setFunctionTimeMetric(metrics, "timeRootMillis", "(root)");
            setFunctionTimeMetric(metrics, "timeProgramMillis", "(program)");
            setFunctionTimeMetric(metrics, "timeGCMillis", "(garbage collector)");
            setFunctionTimeMetric(metrics, "timeIdleMillis", "(idle)");
            metrics.put("numIslands", numIslands);
            metrics.put("maxDepth", maxDepth);

        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return metrics;
    }

    private void setFunctionTimeMetric(JSONObject json, String name, String functionName) throws JSONException {
        if (functionToInfo.containsKey(functionName)) {
            json.put(name, Math.round(functionToInfo.get(functionName).totalTimeMicros * .001));
        }
    }

    @SuppressWarnings("unchecked")
    private int traverseCPUProfileNodes(Map<String, ?> node) {
        // "functionName": "(root)",
        // "scriptId": "0",
        // "url": "",
        // "lineNumber": 0,
        // "columnNumber": 0,
        // "hitCount": 0,
        // "callUID": 2788870597,
        // "children": [
        // {
        // "functionName": "(program)",
        // "scriptId": "0",
        // ...

        depth++;
        if (maxDepth < depth) {
            maxDepth = depth;
        }

        // populate functionToInfo
        String functionName = (String) node.get("functionName");
        CPUProfileInfo info = functionToInfo.get(functionName);
        if (info == null) {
            // NOTE: we do cummulative, not per node
            info = new CPUProfileInfo(functionName);
            functionToInfo.put(functionName, info);
        }

        // calculate self/total time
        int totalHitCount = ((Number) node.get("hitCount")).intValue();
        info.selfTimeMicros += totalHitCount * samplingIntervalMicros;
        for (Map<String, ?> child : (List<Map<String, ?>>) node.get("children")) {
            totalHitCount += traverseCPUProfileNodes(child);
        }
        info.totalTimeMicros += totalHitCount * samplingIntervalMicros;

        // populate idToNode
        idToNode.put((Number) node.get("id"), node);

        depth--;
        return totalHitCount;
    }

    private void printInfo(PrintStream out) {
        List<CPUProfileInfo> nodes = Lists.newArrayList(functionToInfo.values());
        Collections.sort(nodes);
        for (CPUProfileInfo node : nodes) {
            out.println(node);
        }
    }

    /**
     * CPUProfileNode derived data
     */
    private static class CPUProfileInfo implements Comparable<CPUProfileInfo> {
        private final String functionName;

        double selfTimeMicros;
        double totalTimeMicros;

        CPUProfileInfo(String functionName) {
            this.functionName = functionName;
        }

        @Override
        public String toString() {
            return functionName + "[self " + selfTimeMicros / 1000 + "ms, total " + totalTimeMicros / 1000 + " ms]";
        }

        /**
         * Comparable by totalTime
         */
        @Override
        public int compareTo(CPUProfileInfo o) {
            return (int) (o.totalTimeMicros - totalTimeMicros);
        }
    }
}
