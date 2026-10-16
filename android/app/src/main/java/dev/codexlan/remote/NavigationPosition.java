package dev.codexlan.remote;

/** Shared, bounded coordinate mapping for discrete touch controls. */
final class NavigationPosition {
    static boolean cancelLeft(float startX,float x,float threshold){return startX-x>=threshold;}
    static int at(float position,float start,float end,int count){
        if(count<=0)return -1;
        if(count==1||end<=start)return 0;
        float fraction=Math.max(0,Math.min(1,(position-start)/(end-start)));
        return Math.round(fraction*(count-1));
    }
}
