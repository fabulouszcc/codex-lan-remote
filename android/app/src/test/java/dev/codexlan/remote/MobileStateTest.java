package dev.codexlan.remote;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

public class MobileStateTest {
    @Test public void quickNavigationShowsHumanAnswerInsteadOfQuestionTransportMarkup() throws Exception {
        JSONObject item=new JSONObject().put("text","<send_user_message_question_reply>\n[{\"questionItemId\":\"private-id\",\"answer\":\"已经解锁\"}]\n</send_user_message_question_reply>");
        assertEquals("已经解锁",ChatRows.navigationText(item));
        assertEquals("hello",ChatRows.navigationText(new JSONObject().put("text","<in-app-browser-context>metadata</in-app-browser-context>\n## My request:\nhello")));
    }
    @Test public void liveThinkingAppearsAtBottomAndStaleOfflineHistoryDoesNotAnimate() throws Exception {
        JSONObject thread=new JSONObject().put("id","chat").put("ready",true).put("activeTurnId","t").put("turns",new JSONArray()).put("activity",new JSONObject().put("label","正在思考…").put("summary","Visible summary"));
        java.util.List<ChatRows.Row> rows=ChatRows.build(thread);assertEquals("activity",rows.get(rows.size()-1).type);assertEquals("Visible summary",rows.get(rows.size()-1).data.getString("summary"));
        thread.put("ready",false);for(ChatRows.Row row:ChatRows.build(thread))assertNotEquals("activity",row.type);
        thread.put("ready",true).put("activeTurnId","");for(ChatRows.Row row:ChatRows.build(thread))assertNotEquals("activity",row.type);
    }
    @Test public void toolsShareOneGroupPerTurnWithoutDroppingRepliesOrFollowups() throws Exception {
        JSONObject turn=new JSONObject("{\"id\":\"t\",\"timeline\":[{\"id\":\"u\",\"type\":\"user\",\"text\":\"ask\"},{\"id\":\"a\",\"type\":\"message\",\"text\":\"checking\"},{\"id\":\"x\",\"type\":\"tool\",\"output\":\"one\"},{\"id\":\"f\",\"type\":\"user\",\"text\":\"followup\"},{\"id\":\"y\",\"type\":\"tool\",\"output\":\"two\"},{\"id\":\"z\",\"type\":\"message\",\"text\":\"done\"}]}");
        JSONObject thread=new JSONObject().put("id","chat").put("turns",new JSONArray().put(turn));
        java.util.List<ChatRows.Row> rows=ChatRows.build(thread);
        assertEquals(5,rows.size());assertEquals("tools",rows.get(2).type);assertEquals(2,rows.get(2).data.getJSONArray("items").length());
        assertEquals("followup",rows.get(3).data.getString("text"));assertEquals("done",rows.get(4).data.getString("text"));
        String signature=rows.get(2).signature;turn.getJSONArray("timeline").getJSONObject(4).put("output","changed");
        java.util.List<ChatRows.Row> changed=ChatRows.build(thread);assertEquals(rows.get(2).key,changed.get(2).key);assertNotEquals(signature,changed.get(2).signature);assertEquals(rows.get(0).signature,changed.get(0).signature);
    }
    @Test public void rowProjectionKeepsOldHistoryAndApprovalWithoutMixingToolGroups() throws Exception {
        JSONArray turns=new JSONArray();for(int i=0;i<120;i++)turns.put(new JSONObject().put("id","t"+i).put("timeline",new JSONArray().put(new JSONObject().put("id","u").put("type","user").put("text","message "+i)).put(new JSONObject().put("id","tool").put("type","tool"))));
        JSONObject thread=new JSONObject().put("id","chat").put("turns",turns).put("approvals",new JSONArray().put(new JSONObject().put("id",7)));
        java.util.List<ChatRows.Row> rows=ChatRows.build(thread);assertEquals(241,rows.size());assertEquals("message 0",rows.get(0).data.getString("text"));assertEquals("approval",rows.get(240).type);
        java.util.Set<String> keys=new java.util.HashSet<>();for(ChatRows.Row row:rows)assertTrue(keys.add(row.key));
    }
    @Test public void directAddressesDefaultToPinnedHttpsAndRejectCredentialOrPlaintextUrls() throws Exception {
        assertEquals("https://192.168.8.2:9443",MobileState.connectionAddress("192.168.8.2"));
        assertEquals("https://192.168.8.2:9444",MobileState.connectionAddress("https://192.168.8.2:9444/"));
        for(String bad:new String[]{"http://192.168.8.2","https://user:pass@example.com","https://example.com/path","https://example.com?q=x","https://example.com#token","https://example.com:99999"}){
            try{MobileState.connectionAddress(bad);fail("Accepted invalid address");}catch(Exception expected){}
        }
    }
    @Test public void primaryActionFollowsTaskAndDraftWithoutEnablingOfflineInterrupt() throws Exception {
        JSONObject thread=new JSONObject().put("ready",true).put("writable",true).put("activeTurnId","");JSONObject state=new JSONObject().put("online",true);
        assertEquals("none",MobileState.composerAction(state,thread,false,"  "));assertEquals("start",MobileState.composerAction(state,thread,false,"hello"));
        thread.put("activeTurnId","active");assertEquals("interrupt",MobileState.composerAction(state,thread,false,"\n "));assertEquals("steer",MobileState.composerAction(state,thread,false,"follow up"));
        assertEquals("none",MobileState.composerAction(state,thread,true,""));state.put("online",false);assertEquals("none",MobileState.composerAction(state,thread,false,""));
        state.put("online",true);thread.put("ready",false);assertEquals("none",MobileState.composerAction(state,thread,false,"hello"));
    }
    @Test public void orderedTranscriptKeepsDesktopFollowupAndToolPositionsAndLegacyCacheRemainsReadable() throws Exception {
        JSONArray ordered=new JSONArray("[{\"type\":\"user\",\"text\":\"one\"},{\"type\":\"message\",\"text\":\"checking\"},{\"type\":\"tool\",\"id\":\"t\"},{\"type\":\"user\",\"text\":\"desktop followup\"},{\"type\":\"message\",\"text\":\"done\"}]");
        JSONObject turn=new JSONObject().put("input","one\n\ndesktop followup").put("timeline",ordered);assertEquals(ordered.toString(),MobileState.timeline(turn).toString());
        JSONObject legacy=new JSONObject("{\"input\":\"hello\",\"agentMessages\":[\"checking\",\"done\"],\"itemTypes\":[\"userMessage\",\"agentMessage\",\"commandExecution\",\"agentMessage\"],\"toolResults\":[{\"id\":\"tool\",\"type\":\"commandExecution\"}]}");
        JSONArray result=MobileState.timeline(legacy);assertEquals("user",result.getJSONObject(0).getString("type"));assertEquals("tool",result.getJSONObject(2).getString("type"));assertEquals("done",result.getJSONObject(3).getString("text"));
    }
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
