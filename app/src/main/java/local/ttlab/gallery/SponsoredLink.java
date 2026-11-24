package local.ttlab.gallery;

import java.net.URI;
import java.net.URLDecoder;

/** An ad can open a web page after a tap, never an arbitrary Android intent. */
final class SponsoredLink {
    static String browser(String value){
        if(value==null||value.length()>2048||value.chars().anyMatch(c->c<=32||c==127))return null;
        try{
            URI uri=new URI(value);
            if("https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443))return uri.toASCIIString();
            if(!"tg".equals(uri.getScheme())||!"resolve".equals(uri.getHost())||uri.getRawQuery()==null||uri.getUserInfo()!=null)return null;
            String domain=null;StringBuilder query=new StringBuilder();
            for(String pair:uri.getRawQuery().split("&")){
                String[] entry=pair.split("=",2);String key=URLDecoder.decode(entry[0],"UTF-8");
                if("domain".equals(key)){if(domain!=null||entry.length!=2)return null;domain=URLDecoder.decode(entry[1],"UTF-8");}
                else{if(query.length()>0)query.append('&');query.append(pair);}
            }
            if(domain==null||!domain.matches("[A-Za-z][A-Za-z0-9_]{2,31}"))return null;
            return "https://t.me/"+domain+(query.length()==0?"":"?"+query);
        }catch(Exception malformed){return null;}
    }
}
