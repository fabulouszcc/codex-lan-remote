package dev.codexlan.remote;
import org.json.*;
import java.net.URI;
import java.util.*;

/** Addresses belong to the encrypted paired-computer record, never global credentials. */
final class ConnectionHistory {
    static List<String> addresses(JSONObject connection){
        LinkedHashSet<String> found=new LinkedHashSet<>();if(connection==null)return new ArrayList<>();
        JSONArray saved=connection.optJSONArray("lanHistory");if(saved!=null)for(int i=0;i<saved.length()&&found.size()<12;i++)add(found,saved.optString(i));
        JSONObject profiles=connection.optJSONObject("addresses");if(profiles!=null)add(found,profiles.optString("lan"));return new ArrayList<>(found);
    }
    private static void add(Set<String> rows,String value){try{URI uri=new URI(MobileState.connectionAddress(value));String host=uri.getHost();if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/"))||host==null||!host.matches("(?:\\d{1,3}\\.){3}\\d{1,3}"))return;String[] parts=host.split("\\.");for(String part:parts)if(Integer.parseInt(part)>255)return;int first=Integer.parseInt(parts[0]);if(first==0||first==127||first>=224)return;rows.add("https://"+host+":"+(uri.getPort()<0?9443:uri.getPort()));}catch(Exception ignored){}}
    static JSONObject remember(JSONObject connection,String address,String mode)throws Exception{
        if(!"lan".equals(mode))return connection;LinkedHashSet<String> rows=new LinkedHashSet<>();add(rows,address);rows.addAll(addresses(connection));JSONArray history=new JSONArray();for(String row:rows){if(history.length()==12)break;history.put(row);}connection.put("lanHistory",history);return connection;
    }
}
