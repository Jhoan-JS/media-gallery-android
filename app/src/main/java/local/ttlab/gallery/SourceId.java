package local.ttlab.gallery;

/** Fixed identities keep remote ids and storage paths in their own source. */
final class SourceId {
    static String require(String key){if(!"tato".equals(key)&&!"telegram".equals(key)&&!local(key))throw new IllegalArgumentException("Fuente inválida");return key;}
    static boolean local(String key){return "local".equals(key);}
    static String label(String key){switch(require(key)){case "tato":return "Tato";case "telegram":return "Telegram";default:return "Archivos propios";}}
    static String suffix(String key){return "tato".equals(require(key))?"":"-"+key;}
    static String database(String key){return "library"+suffix(key)+".db";}
    static String vault(String key){return "vault"+suffix(key);}
    static String preferences(String key){return "library-ui"+suffix(key);}
}
