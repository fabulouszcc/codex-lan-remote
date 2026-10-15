package dev.codexlan.remote;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

public class MobileStateTest {
    @Test public void completeDesktopDiffStringAllowsQuickApprovalButUnknownOrTruncatedDiffDoesNot() throws Exception {
        JSONObject change = new JSONObject().put("path", "test.txt").put("diff", "+TEST");
        JSONObject request = new JSONObject().put("kind", "file").put("changes", new JSONArray().put(change));
        assertTrue(MobileState.canQuickApprove(request, "文件变更：" + request.getJSONArray("changes")));
        change.put("truncated", true); assertFalse(MobileState.canQuickApprove(request, "diff"));
        change.remove("truncated"); change.put("diff", new JSONObject()); assertFalse(MobileState.canQuickApprove(request, "diff"));
        change.put("diff", JSONObject.NULL); assertFalse(MobileState.canQuickApprove(request, "diff"));
        change.put("diff", " "); assertFalse(MobileState.canQuickApprove(request, "diff"));
        change.put("diff", "+TEST"); assertFalse(MobileState.canQuickApprove(request, new String(new char[1201]).replace('\0', 'x')));
        request.put("truncated", true); assertFalse(MobileState.canQuickApprove(request, "diff"));
    }
    @Test public void nullCommandCannotBeApprovedFromNotification() throws Exception {
        JSONObject request = new JSONObject().put("kind", "command").put("command", JSONObject.NULL);
        assertFalse(MobileState.canQuickApprove(request, "null"));
        request.put("command", "echo TEST"); assertTrue(MobileState.canQuickApprove(request, "echo TEST"));
    }
    @Test public void androidJsonCoercesNullToLiteralStringButIdleMustRemainIdle() throws Exception {
        JSONObject thread = new JSONObject("{\"ready\":true,\"activeTurnId\":null}");
        assertEquals("null", thread.optString("activeTurnId", ""));
        assertEquals("", MobileState.activeTurnId(thread));
        thread.put("activeTurnId", "");
        assertEquals("", MobileState.activeTurnId(thread));
        thread.put("activeTurnId", 123);
        assertEquals("", MobileState.activeTurnId(thread));
        thread.put("activeTurnId", "null");
        assertEquals("", MobileState.activeTurnId(thread));
    }
    @Test public void explicitLiveIdleOverridesStaleCachedActiveTurn() throws Exception {
        JSONObject thread = state("inProgress", "", true).getJSONArray("threads").getJSONObject(0);
        thread.put("activeTurnId", JSONObject.NULL);
        assertEquals("", MobileState.activeTurnId(thread));
        thread.put("activeTurnId", "actual-turn");
        assertEquals("actual-turn", MobileState.activeTurnId(thread));
        thread.put("ready", false);
        assertEquals("", MobileState.activeTurnId(thread));
    }
    @Test public void olderGatewayIgnoresHistoricalActiveTurnsBeforeLatestCompletedTurn() throws Exception {
        JSONObject thread = state("inProgress", "", true).getJSONArray("threads").getJSONObject(0);
        thread.getJSONArray("turns").put(new JSONObject().put("id", "newer").put("status", "completed"));
        assertEquals("", MobileState.activeTurnId(thread));
        thread.getJSONArray("turns").getJSONObject(1).put("status", "inProgress");
        assertEquals("newer", MobileState.activeTurnId(thread));
    }
    @Test public void unavailableActionsExplainOfflineReadOnlyLoadingAndSubmission() throws Exception {
        JSONObject online = state("completed", "", true).put("online", true);
        JSONObject thread = online.getJSONArray("threads").getJSONObject(0);
        assertEquals("", MobileState.unavailableReason(online, thread, false));
        assertTrue(MobileState.unavailableReason(online, thread, true).contains("提交"));
        thread.put("writable", false);
        assertTrue(MobileState.unavailableReason(online, thread, false).contains("读取"));
        thread.put("ready", false);
        assertTrue(MobileState.unavailableReason(online, thread, false).contains("电脑打开"));
        assertTrue(MobileState.unavailableReason(online, null, false).contains("加载"));
        assertTrue(MobileState.unavailableReason(MobileState.offline(online), thread, false).contains("连接中断"));
    }
    private JSONObject state(String status, String text, boolean ready) throws Exception {
        JSONObject turn = new JSONObject().put("id", "turn-1").put("status", status).put("input", "remember BLUE-731")
            .put("agentMessages", new JSONArray().put(text));
        JSONObject thread = new JSONObject().put("id", "chat-a").put("ready", ready).put("turns", new JSONArray().put(turn)).put("approvals", new JSONArray());
        return new JSONObject().put("threads", new JSONArray().put(thread)).put("selectedThreadId", "chat-a");
    }
    @Test public void reconnectPlaceholderDoesNotEraseSavedMessages() throws Exception {
        JSONObject before = state("completed", "BLUE-731", true);
        JSONObject thin = new JSONObject("{\"threads\":[{\"id\":\"chat-a\",\"ready\":false,\"turns\":[],\"approvals\":[]}]}");
        JSONObject result = MobileState.merge(before, thin);
        assertEquals("BLUE-731", result.getJSONArray("threads").getJSONObject(0).getJSONArray("turns").getJSONObject(0).getJSONArray("agentMessages").getString(0));
        assertEquals("chat-a", result.getString("selectedThreadId"));
        assertFalse(result.getJSONArray("threads").getJSONObject(0).getBoolean("ready"));
    }
    @Test public void incompleteLiveTurnDoesNotReplacePersistedText() throws Exception {
        JSONObject before = state("completed", "saved reply", true), thin = state("completed", "", true);
        thin.getJSONArray("threads").getJSONObject(0).getJSONArray("turns").getJSONObject(0).put("agentMessages", new JSONArray()).put("input", "");
        JSONObject result = MobileState.merge(before, thin).getJSONArray("threads").getJSONObject(0).getJSONArray("turns").getJSONObject(0);
        assertEquals("saved reply", result.getJSONArray("agentMessages").getString(0));
        assertEquals("remember BLUE-731", result.getString("input"));
    }
    @Test public void offlineViewNeverRetainsActionableApprovals() throws Exception {
        JSONObject before = state("inProgress", "", true);
        before.getJSONArray("threads").getJSONObject(0).put("approvals", new JSONArray().put(new JSONObject().put("id", 15)));
        JSONObject offline = MobileState.offline(before);
        assertFalse(offline.getJSONArray("threads").getJSONObject(0).getBoolean("ready"));
        assertEquals(0, offline.getJSONArray("threads").getJSONObject(0).getJSONArray("approvals").length());
        assertEquals(1, before.getJSONArray("threads").getJSONObject(0).getJSONArray("approvals").length());
    }
    @Test public void completionAlertsOnlyForObservedActiveTurnsIncludingReconnect() throws Exception {
        JSONObject completed = state("completed", "DONE", true);
        assertEquals(0, MobileState.alerts(null, completed).length());
        assertEquals(1, MobileState.alerts(state("inProgress", "", true), completed).length());
        assertEquals(0, MobileState.alerts(completed, completed).length());
        assertEquals(1, MobileState.alerts(MobileState.offline(state("inProgress", "", true)), completed).length());
    }
    @Test public void pendingApprovalNotifiesImmediatelyButNotWhileOfflineOrSubmitting() throws Exception {
        JSONObject current = state("inProgress", "", true);
        JSONObject request = new JSONObject().put("id", 15).put("kind", "file");
        current.getJSONArray("threads").getJSONObject(0).getJSONArray("approvals").put(request);
        assertEquals("approval", MobileState.alerts(null, current).getJSONObject(0).getString("kind"));
        request.put("submitting", true); assertEquals(0, MobileState.alerts(null, current).length());
        assertEquals(0, MobileState.alerts(null, MobileState.offline(current)).length());
    }
    @Test public void cachedOtherChatRemainsReadableButNotLive() throws Exception {
        JSONObject before = state("completed", "old", true), incoming = state("completed", "new", true);
        incoming.getJSONArray("threads").getJSONObject(0).put("id", "chat-b");
        JSONObject result = MobileState.merge(before, incoming);
        assertEquals(2, result.getJSONArray("threads").length());
        assertFalse(result.getJSONArray("threads").getJSONObject(0).getBoolean("ready"));
        assertTrue(result.getJSONArray("threads").getJSONObject(1).getBoolean("ready"));
    }
    @Test public void shortTaskCompletingBetweenPollsNotifiesButHistoricalHydrationDoesNot() throws Exception {
        JSONObject before = state("completed", "old", true); before.put("online", true);
        before.getJSONArray("threads").getJSONObject(0).put("historyReady", true).getJSONArray("turns").getJSONObject(0).put("startedAtMs", 1000);
        JSONObject after = state("completed", "new", true);
        after.getJSONArray("threads").getJSONObject(0).getJSONArray("turns").getJSONObject(0).put("id", "turn-2").put("startedAtMs", 2000);
        assertEquals(1, MobileState.alerts(before, after).length());
        after.getJSONArray("threads").getJSONObject(0).getJSONArray("turns").getJSONObject(0).put("startedAtMs", 500);
        assertEquals(0, MobileState.alerts(before, after).length());
    }
}
