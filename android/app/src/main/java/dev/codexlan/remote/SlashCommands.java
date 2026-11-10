package dev.codexlan.remote;

import java.util.*;

final class SlashCommands {
    static final Map<String,String> BUILTINS=new LinkedHashMap<>();
    static {BUILTINS.put("new","新建聊天");BUILTINS.put("model","选择模型");BUILTINS.put("status","查看连接与聊天状态");BUILTINS.put("usage","查看用量");BUILTINS.put("compact","压缩当前聊天上下文");BUILTINS.put("skills","查看电脑技能");}
    static boolean suggesting(String text){return text!=null&&text.matches("/[\\w.-]*");}
    static String builtin(String text){String command=text.trim();return command.startsWith("/")&&BUILTINS.containsKey(command.substring(1))?command.substring(1):"";}
    static boolean matches(String text,String name){return suggesting(text)&&name.toLowerCase(Locale.ROOT).startsWith(text.substring(1).toLowerCase(Locale.ROOT));}
}
