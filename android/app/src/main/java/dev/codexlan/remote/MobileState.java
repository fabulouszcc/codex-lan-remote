package dev.codexlan.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Iterator;

/** Merge wire snapshots without erasing locally retained history on a reconnect. */
final class MobileState {
    static boolean canQuickApprove(JSONObject request, String details) {
        if (details == null || details.trim().isEmpty() || details.length() > 1200 || request.optBoolean("truncated")) return false;
        if ("command".equals(request.optString("kind"))) {
            Object command = request.opt("command");
            return command instanceof String && !((String) command).trim().isEmpty();
        }
        if (!"file".equals(request.optString("kind"))) return false;
        JSONArray changes = request.optJSONArray("changes");
        if (changes == null || changes.length() == 0) return false;
        for (int i = 0; i < changes.length(); i++) {
            JSONObject change = changes.optJSONObject(i);
            if (change == null || change.optBoolean("truncated")) return false;
            Object diff = change.opt("diff");
            // The desktop's actual file-change schema supplies a complete diff
            // string. Unknown object encodings must be reviewed in the chat.
            if (!(diff instanceof String) || ((String) diff).trim().isEmpty()) return false;
        }
        return true;
    }
    static String activeTurnId(JSONObject thread) {
        if (thread == null || !thread.optBoolean("ready")) return "";
        if (thread.has("activeTurnId")) {
            Object id = thread.opt("activeTurnId");
            return id instanceof String && !"null".equals(id) ? (String) id : "";
        }
        // Compatibility with older gateways: only the latest live turn counts.
        JSONArray turns = thread.optJSONArray("turns");
        JSONObject last = turns == null || turns.length() == 0 ? null : turns.optJSONObject(turns.length() - 1);
        return last != null && "inProgress".equals(last.optString("status")) ? last.optString("id") : "";
    }
    static String unavailableReason(JSONObject state, JSONObject thread, boolean sending) {
        if (sending) return "正在提交，请稍候…";
        if (state == null || !state.optBoolean("online")) return "连接中断，请检查 EasyTier 或重新连接";
        if (thread == null) return "正在加载所选聊天，请稍候";
        if (!thread.optBoolean("ready")) return "聊天历史可读，请先在电脑打开该聊天，或点击重新同步";
        if (!thread.optBoolean("writable", true)) return "此聊天仅允许读取，需要在电脑授权后发送";
        return "";
    }
    static JSONObject merge(JSONObject previous, JSONObject incoming) throws Exception {
        LinkedHashMap<String, JSONObject> threads = new LinkedHashMap<>();
        for (JSONObject source : new JSONObject[] { previous, incoming }) {
            JSONArray values = source == null ? null : source.optJSONArray("threads");
            if (values == null) continue;
            for (int i = 0; i < values.length(); i++) {
                JSONObject thread = new JSONObject(values.getJSONObject(i).toString());
                if (source == previous) thread.put("ready", false).put("approvals", new JSONArray());
                String id = thread.getString("id"); JSONObject old = threads.get(id);
                LinkedHashMap<String, JSONObject> turns = new LinkedHashMap<>();
                for (JSONObject version : new JSONObject[] { old, thread }) {
                    JSONArray entries = version == null ? null : version.optJSONArray("turns"); if (entries == null) continue;
                    for (int j = 0; j < entries.length(); j++) {
                        JSONObject next = new JSONObject(entries.getJSONObject(j).toString()); String key = next.getString("id");
                        JSONObject prior = turns.get(key);
                        if (prior != null) {
                            if (next.optString("input").isEmpty()) next.put("input", prior.optString("input"));
                            if (next.optJSONArray("agentMessages") == null || next.getJSONArray("agentMessages").length() == 0) next.put("agentMessages", prior.optJSONArray("agentMessages"));
                        }
                        turns.put(key, next);
                    }
                }
                while (turns.size() > 50) turns.remove(turns.keySet().iterator().next());
                thread.put("turns", new JSONArray(turns.values())); threads.put(id, thread);
            }
        }
        // Bound encrypted phone cache; the desktop remains the complete history source.
        while (threads.size() > 24) threads.remove(threads.keySet().iterator().next());
        JSONObject result = new JSONObject().put("threads", new JSONArray(threads.values()));
        if (previous != null) result.put("selectedThreadId", previous.optString("selectedThreadId"));
        if (incoming != null) result.put("version", incoming.optLong("version")).put("savedAt", System.currentTimeMillis());
        return result;
    }
    static JSONObject offline(JSONObject value) throws Exception {
        JSONObject result = new JSONObject(value.toString()); result.put("online", false);
        JSONArray threads = result.optJSONArray("threads");
        if (threads != null) for (int i = 0; i < threads.length(); i++) threads.getJSONObject(i).put("ready", false).put("approvals", new JSONArray());
        return result;
    }
    static JSONArray alerts(JSONObject previous, JSONObject current) throws Exception {
        LinkedHashMap<String, String> statuses = new LinkedHashMap<>();
        LinkedHashMap<String, Long> liveBaselines = new LinkedHashMap<>();
        JSONArray before = previous == null ? null : previous.optJSONArray("threads");
        if (before != null) for (int i = 0; i < before.length(); i++) {
            JSONArray turns = before.getJSONObject(i).optJSONArray("turns"); if (turns == null) continue;
            JSONObject thread = before.getJSONObject(i); long latest = 0;
            for (int j = 0; j < turns.length(); j++) { JSONObject turn = turns.getJSONObject(j); statuses.put(thread.getString("id") + ":" + turn.getString("id"), turn.optString("status")); latest = Math.max(latest, turn.optLong("startedAtMs")); }
            if (previous.optBoolean("online") && thread.optBoolean("ready") && thread.optBoolean("historyReady")) liveBaselines.put(thread.getString("id"), latest);
        }
        JSONArray result = new JSONArray(), threads = current.optJSONArray("threads");
        if (threads == null) return result;
        for (int i = 0; i < threads.length(); i++) {
            JSONObject thread = threads.getJSONObject(i); String threadId = thread.getString("id");
            if (!thread.optBoolean("ready")) continue;
            JSONArray turns = thread.optJSONArray("turns");
            if (turns != null) for (int j = 0; j < turns.length(); j++) {
                JSONObject turn = turns.getJSONObject(j); String id = turn.getString("id"), status = turn.optString("status");
                boolean finished = status.equals("completed") || status.equals("failed");
                boolean newlyFinished = !statuses.containsKey(threadId + ":" + id) && liveBaselines.containsKey(threadId)
                    && turn.optLong("startedAtMs") > liveBaselines.get(threadId);
                if (finished && ("inProgress".equals(statuses.get(threadId + ":" + id)) || newlyFinished))
                    result.put(new JSONObject().put("kind", "result").put("threadId", threadId).put("turnId", id).put("status", status));
            }
            JSONArray approvals = thread.optJSONArray("approvals");
            if (approvals != null) for (int j = 0; j < approvals.length(); j++) {
                JSONObject approval = approvals.getJSONObject(j);
                if (!approval.optBoolean("submitting")) result.put(new JSONObject().put("kind", "approval").put("threadId", threadId).put("request", approval));
            }
        }
        return result;
    }
}
