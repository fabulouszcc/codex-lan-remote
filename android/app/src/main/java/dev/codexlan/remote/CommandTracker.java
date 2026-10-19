package dev.codexlan.remote;

import java.util.HashMap;
import java.util.Map;

/** Submission ownership is per chat, and an old callback cannot clear a newer one. */
final class CommandTracker {
    private final Map<String,String> pending=new HashMap<>();
    boolean pending(String chat){return pending.containsKey(chat);}
    boolean begin(String chat,String operation){if(pending(chat))return false;pending.put(chat,operation);return true;}
    void finish(String chat,String operation){if(operation.equals(pending.get(chat)))pending.remove(chat);}
    void clear(){pending.clear();}
}
