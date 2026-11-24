package local.ttlab.gallery;
import android.os.Bundle;import org.json.JSONObject;import java.util.*;
final class SavedCollection {
 final long id,updated;final String name;final MediaFilter filter;
 SavedCollection(long id,String name,MediaFilter filter,long updated){this.id=id;this.name=checkedName(name);this.filter=filter.copy();this.updated=updated;}
 static String checkedName(String value){if(value==null)throw new IllegalArgumentException("Escribe un nombre");String name=value.trim();if(name.isEmpty()||name.length()>60||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Usa un nombre de 1 a 60 caracteres");return name;}
 private static Bundle portable(MediaFilter filter){Bundle b=filter.bundle();for(String field:Arrays.asList("stacked","stackKey","stackMime","stackSize"))b.remove(field);return b;}
 static String encode(MediaFilter filter){filter.check();if(!filter.stackKey.isEmpty())throw new IllegalArgumentException("Guarda consultas desde Biblioteca");if(filter.query.length()>4096||filter.query.indexOf('\0')>=0)throw new IllegalArgumentException("La búsqueda es demasiado larga");try{JSONObject json=new JSONObject();json.put("version",2);Bundle bundle=portable(filter);for(String name:bundle.keySet())json.put(name,bundle.get(name));String result=json.toString();if(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("La búsqueda es demasiado larga");return result;}catch(org.json.JSONException invalid){throw new IllegalArgumentException("Filtros inválidos",invalid);}}
 static MediaFilter decode(String value){
  try{
   if(value==null||value.length()>16384)throw new IllegalArgumentException();JSONObject json=new JSONObject(value);Object version=json.get("version");
   if(!(version instanceof Integer)||((Integer)version!=1&&(Integer)version!=2))throw new IllegalArgumentException();
   Bundle template=portable(new MediaFilter()),bundle=new Bundle(template);Set<String> fields=new HashSet<>(template.keySet());
   if((Integer)version==1)fields.removeAll(Arrays.asList("albumId","tagId","recent"));
   Set<String> expected=new HashSet<>(fields);expected.add("version");if(json.length()!=expected.size())throw new IllegalArgumentException();Iterator<String> names=json.keys();while(names.hasNext())if(!expected.contains(names.next()))throw new IllegalArgumentException();
   for(String name:fields){Object original=template.get(name),input=json.get(name);
    if(original instanceof String){if(!(input instanceof String))throw new IllegalArgumentException();bundle.putString(name,(String)input);}
    else if(original instanceof Boolean){if(!(input instanceof Boolean))throw new IllegalArgumentException();bundle.putBoolean(name,(Boolean)input);}
    else{if(!(input instanceof Integer)&&!(input instanceof Long))throw new IllegalArgumentException();long number=((Number)input).longValue();if(original instanceof Integer){if(number<Integer.MIN_VALUE||number>Integer.MAX_VALUE)throw new IllegalArgumentException();bundle.putInt(name,(int)number);}else bundle.putLong(name,number);}
   }
   MediaFilter filter=MediaFilter.from(bundle);encode(filter);return filter;
  }catch(Exception invalid){throw new IllegalArgumentException("La colección contiene filtros inválidos",invalid);}
 }
}
