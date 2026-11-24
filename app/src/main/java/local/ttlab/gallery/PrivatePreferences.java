package local.ttlab.gallery;
import android.content.*;import org.json.*;import java.util.*;import java.nio.charset.StandardCharsets;
/** Encrypt first, then remove the legacy plaintext copy. A restarted migration is idempotent. */
final class PrivatePreferences implements SharedPreferences {
 private final Vault vault;private final SharedPreferences legacy;private Map<String,Object> values;
 private PrivatePreferences(Vault vault,SharedPreferences legacy)throws Exception{this.vault=vault;this.legacy=legacy;values=read();if(values==null)values=new HashMap<>(legacy.getAll());persist(values);}
 static SharedPreferences open(Context c,String source,Vault vault)throws Exception{return new PrivatePreferences(vault,c.getSharedPreferences(SourceId.preferences(source),Context.MODE_PRIVATE));}
 private Map<String,Object> read()throws Exception{byte[] bytes=vault.secret("ui-state");if(bytes==null)return null;try{JSONObject json=new JSONObject(new String(bytes,StandardCharsets.UTF_8));Map<String,Object> map=new HashMap<>();Iterator<String> keys=json.keys();while(keys.hasNext()){String name=keys.next();map.put(name,json.get(name));}return map;}finally{Arrays.fill(bytes,(byte)0);}}
 private void persist(Map<String,Object> map)throws Exception{byte[] bytes=new JSONObject(map).toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>65536)throw new IllegalArgumentException("Preferencias demasiado grandes");try{vault.secret("ui-state",bytes);}finally{Arrays.fill(bytes,(byte)0);}if(!legacy.edit().clear().putInt("columns",((Number)map.getOrDefault("columns",3)).intValue()).commit())throw new java.io.IOException("No se pudo retirar la copia anterior de los filtros");}
 public synchronized Map<String,?> getAll(){return new HashMap<>(values);}
 public synchronized String getString(String k,String d){Object v=values.get(k);return v instanceof String?(String)v:d;}
 public Set<String> getStringSet(String k,Set<String>d){throw new UnsupportedOperationException();}
 public synchronized int getInt(String k,int d){Object v=values.get(k);return v instanceof Number?((Number)v).intValue():d;}
 public synchronized long getLong(String k,long d){Object v=values.get(k);return v instanceof Number?((Number)v).longValue():d;}
 public synchronized float getFloat(String k,float d){Object v=values.get(k);return v instanceof Number?((Number)v).floatValue():d;}
 public synchronized boolean getBoolean(String k,boolean d){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:d;}
 public synchronized boolean contains(String k){return values.containsKey(k);}
 public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){throw new UnsupportedOperationException();}
 public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
 public Editor edit(){return new Editor(){final Map<String,Object> changes=new HashMap<>();boolean clear;
  public Editor putString(String k,String v){changes.put(k,v);return this;}public Editor putStringSet(String k,Set<String>v){throw new UnsupportedOperationException();}
  public Editor putInt(String k,int v){changes.put(k,v);return this;}public Editor putLong(String k,long v){changes.put(k,v);return this;}public Editor putFloat(String k,float v){changes.put(k,v);return this;}public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}
  public Editor remove(String k){changes.put(k,null);return this;}public Editor clear(){clear=true;return this;}
  public boolean commit(){synchronized(PrivatePreferences.class){try{Map<String,Object> map=clear?new HashMap<>():read();if(map==null)map=new HashMap<>();for(Map.Entry<String,Object> e:changes.entrySet()){if(e.getValue()==null)map.remove(e.getKey());else map.put(e.getKey(),e.getValue());}persist(map);synchronized(PrivatePreferences.this){values=map;}return true;}catch(Exception failure){return false;}}}
  public void apply(){if(!commit())throw new IllegalStateException("No se pudieron guardar los filtros cifrados");}
 };}
}
