package dev.codexlan.remote;

/** UI-thread gate: one preparation at a time, with forward progress under streaming. */
final class RenderGate {
    private boolean running;
    private int appliedRevision=-1;
    boolean begin(){if(running)return false;running=true;return true;}
    boolean complete(int revision,boolean samePage){
        running=false;
        if(!samePage||revision<=appliedRevision)return false;
        appliedRevision=revision;return true;
    }
    void failed(){running=false;}
}
