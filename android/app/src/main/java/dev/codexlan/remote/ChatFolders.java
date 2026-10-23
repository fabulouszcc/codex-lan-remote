package dev.codexlan.remote;

import android.app.Activity;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Virtualized sidebar folders; expansion is remembered independently per computer. */
final class ChatFolders extends ListView {
    private final Activity activity;private final String computer,selected;private final Consumer<String> open,create;
    private final UiPalette palette;private final android.content.SharedPreferences preferences;
    private List<JSONObject> rows;private final List<JSONObject> visible=new ArrayList<>();private final Set<String> headers=new HashSet<>();private String query="",signature="",animateGroup="";
    private final BaseAdapter adapter=new BaseAdapter(){public int getCount(){return visible.size();}public JSONObject getItem(int i){return visible.get(i);}public long getItemId(int i){return i;}public int getViewTypeCount(){return 2;}public int getItemViewType(int i){return headers.contains(getItem(i).optString("id"))?0:1;}
        public View getView(int position,View reused,ViewGroup parent){JSONObject row=getItem(position);boolean header=getItemViewType(position)==0;LinearLayout box=reused instanceof LinearLayout?(LinearLayout)reused:new LinearLayout(activity);box.removeAllViews();box.setOrientation(LinearLayout.HORIZONTAL);box.setGravity(Gravity.CENTER_VERTICAL);box.setPadding(dp(header?12:38),dp(8),dp(10),dp(8));box.setMinimumHeight(dp(header?52:66));
            GradientDrawable shape=new GradientDrawable();shape.setCornerRadius(dp(14));shape.setColor(!header&&row.optString("id").equals(selected)?(palette.dark?0xff232724:0xfff0f4f1):header?(palette.dark?0xff303632:0xffe6eee8):android.graphics.Color.TRANSPARENT);box.setBackground(shape);
            if(header){ImageView folder=new ImageView(activity);folder.setImageDrawable(new UiIcon(row.optString("groupId").equals("pinned")?"pin":"folder",palette.accent));box.addView(folder,new LinearLayout.LayoutParams(dp(21),dp(21)));}
            LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(0,-2,1);space.leftMargin=dp(header?10:0);space.rightMargin=dp(6);box.addView(labels,space);
            TextView title=text(header?row.optString("groupName","其他会话"):row.optString("name","桌面聊天"),header?14:15,palette.foreground);title.setMaxLines(header?1:2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);if(header)title.setTypeface(null,Typeface.BOLD);labels.addView(title);
            if(header){TextView count=text(String.valueOf(row.optInt("count")),12,palette.muted);count.setPadding(dp(6),0,dp(8),0);box.addView(count);ImageView arrow=new ImageView(activity);arrow.setImageDrawable(new UiIcon("next",palette.muted));boolean expanded=expanded(row.optString("groupId"));arrow.setRotation(expanded?90:0);box.addView(arrow,new LinearLayout.LayoutParams(dp(15),dp(15)));box.setContentDescription(title.getText()+"，"+(expanded?"收起":"展开")+"，"+row.optInt("count")+" 个会话");box.setOnClickListener(v->{if(!query.isEmpty())return;String group=row.optString("groupId");preferences.edit().putBoolean(computer+":"+group,!expanded).apply();arrow.animate().rotation(expanded?0:90).setDuration(140).start();if(!expanded){animateGroup=group;postDelayed(()->animateGroup="",350);}postDelayed(()->rebuild(true),100);});}
            else{String detail=row.optString("id").equals(selected)?"当前会话":row.optBoolean("ready")?"实时已连接":row.optBoolean("historyReady")?"历史已同步":"正在同步历史";TextView subtitle=text(detail,11,palette.muted);subtitle.setPadding(0,dp(3),0,0);labels.addView(subtitle);box.setOnClickListener(v->open.accept(row.optString("id")));if(animateGroup.equals(row.optString("groupId","chats"))){box.setAlpha(.3f);box.setTranslationY(-dp(4));box.animate().alpha(1).translationY(0).setDuration(150).start();}else{box.setAlpha(1);box.setTranslationY(0);}}
            if(header&&row.optBoolean("projectPinned")){ImageView pin=new ImageView(activity);pin.setImageDrawable(new UiIcon("pin",palette.muted));LinearLayout.LayoutParams pinSpace=new LinearLayout.LayoutParams(dp(16),dp(16));pinSpace.rightMargin=dp(8);box.addView(pin,pinSpace);}
            if(header&&row.optString("baseThreadId").length()>0){ImageView add=new ImageView(activity);add.setImageDrawable(new UiIcon("plus",palette.foreground));add.setPadding(dp(9),dp(9),dp(9),dp(9));add.setContentDescription("在"+row.optString("groupName")+"中新建会话");box.addView(add,new LinearLayout.LayoutParams(dp(36),dp(36)));add.setOnClickListener(v->create.accept(row.optString("baseThreadId")));}
            return box;
        }};
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){JSONObject state=SessionService.latest;if(state==null||!computer.equals(state.optString("computer")))return;try{rows=ChatCatalog.rows(state);rebuild(false);}catch(Exception ignored){}}};
    ChatFolders(Activity activity,List<JSONObject> rows,String computer,String selected,Consumer<String> open,Consumer<String> create){super(activity);this.activity=activity;this.rows=rows;this.computer=computer;this.selected=selected;this.open=open;this.create=create;palette=UiPalette.of(activity);preferences=activity.getSharedPreferences("chatFolders",Context.MODE_PRIVATE);setDivider(null);setSelector(android.R.color.transparent);setPadding(0,dp(2),0,dp(8));setClipToPadding(false);setAdapter(adapter);rebuild(true);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView view=new TextView(activity);view.setText(value);view.setTextColor(color);view.setTextSize(size);return view;}
    private boolean expanded(String group){boolean current=false;for(JSONObject row:rows)if(row.optString("id").equals(selected)&&row.optString("groupId","chats").equals(group))current=true;return !query.isEmpty()||preferences.getBoolean(computer+":"+group,current||group.equals("pinned"));}
    void query(String value){query=value.trim().toLowerCase(Locale.ROOT);rebuild(true);}
    private void rebuild(boolean force){StringBuilder next=new StringBuilder(query);for(JSONObject row:rows)next.append(row.optString("id")).append(row.optString("name")).append(row.optString("groupId")).append(row.optString("groupName")).append(row.optBoolean("projectPinned")).append(row.optInt("groupOrder")).append(row.optInt("order")).append(row.optBoolean("ready")).append(row.optBoolean("historyReady"));if(!force&&next.toString().equals(signature))return;signature=next.toString();
        int first=getFirstVisiblePosition(),offset=getChildCount()==0?0:getChildAt(0).getTop();visible.clear();headers.clear();LinkedHashMap<String,List<JSONObject>> groups=new LinkedHashMap<>();for(JSONObject row:rows){if(!row.optString("name").toLowerCase(Locale.ROOT).contains(query)&&!row.optString("groupName").toLowerCase(Locale.ROOT).contains(query))continue;groups.computeIfAbsent(row.optString("groupId","chats"),k->new ArrayList<>()).add(row);}
        for(Map.Entry<String,List<JSONObject>> group:groups.entrySet()){try{JSONObject source=group.getValue().get(0),header=new JSONObject().put("id","folder:"+group.getKey()).put("groupId",group.getKey()).put("groupName",source.optString("groupName","其他会话")).put("count",group.getValue().size()).put("projectPinned",source.optBoolean("projectPinned")).put("baseThreadId",group.getKey().startsWith("project:")?source.optString("id"):"");headers.add(header.getString("id"));visible.add(header);if(expanded(group.getKey()))visible.addAll(group.getValue());}catch(Exception ignored){}}
        if(visible.isEmpty())try{JSONObject empty=new JSONObject().put("id","folder:empty").put("groupId","empty").put("groupName","没有找到匹配的会话").put("count",0);headers.add("folder:empty");visible.add(empty);}catch(Exception ignored){}
        adapter.notifyDataSetChanged();if(!force)setSelectionFromTop(first,offset);
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();IntentFilter filter=new IntentFilter(SessionService.UPDATE);if(android.os.Build.VERSION.SDK_INT>=33)activity.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else activity.registerReceiver(receiver,filter);}
    @Override protected void onDetachedFromWindow(){activity.unregisterReceiver(receiver);super.onDetachedFromWindow();}
}
