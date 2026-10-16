package dev.codexlan.remote;

/** A bounded window keeps touch targets apart when the keyboard reduces height. */
final class RailWindow {
    int count,capacity,start;
    void configure(int count,int capacity,int anchor,boolean preserve){
        this.count=Math.max(0,count);this.capacity=Math.min(this.count,Math.max(1,capacity));
        start=clamp(preserve?start:anchor-this.capacity/2);
    }
    private int clamp(int value){return Math.max(0,Math.min(Math.max(0,count-capacity),value));}
    float centeredStart(float focus){return Math.max(0,Math.min(Math.max(0,count-capacity),focus-(capacity-1)/2f));}
    static float progress(float position,int from,int to){return to<=from?0:Math.max(0,Math.min(1,(position-from)/(to-from)));}
    boolean step(int direction){int next=clamp(start+Integer.signum(direction));if(next==start)return false;start=next;return true;}
    int at(float y,float top,float bottom){int offset=NavigationPosition.at(y,top,bottom,capacity);return offset<0?-1:start+offset;}
    static float emphasis(float distance){float proximity=Math.max(0,1-Math.abs(distance)/3f);return proximity*proximity;}
}
