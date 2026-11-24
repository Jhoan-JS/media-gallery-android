package local.ttlab.gallery;

/** Consent loss is different from a cold connector, changed selection or interrupted read. */
final class ConnectionMessage {
    static String describe(Throwable error,boolean owner,String source){
        if(SourceId.local(source))return "No se pudo abrir la copia cifrada de este archivo.";
        if(!"telegram".equals(source))return describe(error,owner);
        StringBuilder codes=new StringBuilder();for(int n=0;error!=null&&n<8;n++,error=error.getCause())codes.append(error.getMessage()).append(' ');
        String value=codes.toString();
        if(value.contains("E_TELEGRAM_LOGIN_REQUIRED"))return "Abre Conector TG e inicia sesión en la cuenta vinculada a esta biblioteca.";
        if(value.contains("E_TELEGRAM_API_CONFIG")||value.contains("E_TELEGRAM_SETUP_REQUIRED"))return "Abre Conector TG para configurar tus credenciales de aplicación de Telegram.";
        if(value.contains("E_TELEGRAM_CONSENT_REQUIRED"))return "Vincula Telegram una vez desde Ajustes → Autorizar conexión. La autorización queda guardada.";
        if(value.contains("E_SOURCE_ACCOUNT_CHANGED")||value.contains("otra cuenta")||value.contains("respaldo antiguo"))return "Esta biblioteca conserva su cuenta original de Telegram. Comprueba la cuenta de Conector TG; tus datos y descargas se mantienen.";
        java.util.regex.Matcher wait=java.util.regex.Pattern.compile("E_TELEGRAM_FLOOD_WAIT_([0-9]{1,9})").matcher(value);
        if(wait.find())return "Telegram solicita esperar "+wait.group(1)+" segundos. El avance está guardado; vuelve a actualizar cuando termine la espera.";
        if(value.contains("Instala Conector")||value.contains("E_TELEGRAM_ENGINE_UNAVAILABLE"))return "Instala o actualiza Conector TG en este mismo usuario Android y vuelve a intentarlo.";
        if(value.contains("E_TELEGRAM_UNMAPPED_PEER"))return "Este chat conserva sus copias locales. Selecciónalo de nuevo en Conector TG para reconectar; los respaldos antiguos sin identidad de chat solo se consultan sin conexión.";
        if(value.contains("E_TELEGRAM_NOT_SELECTED")||value.contains("E_TELEGRAM_CHAT_NOT_SELECTED"))return "Elige los chats que quieres explorar en Conector TG.";
        return "No se pudo completar la lectura de Telegram. Pulsa Reintentar para restablecer la conexión; la autorización se conserva.";
    }
    static String describe(Throwable error,boolean owner){
        if(!owner)return "Abre el clon de laboratorio con TestTes en este perfil y vuelve a intentarlo.";
        StringBuilder codes=new StringBuilder();for(int n=0;error!=null&&n<8;n++,error=error.getCause())codes.append(error.getMessage()).append(' ');
        String value=codes.toString();
        if(value.contains("E_ACCOUNT_SELECTION"))return "Selecciona en Conector Tato la cuenta ya vinculada. La autorización sigue guardada; no hace falta volver a vincularla.";
        if(value.contains("E_LOGGED_OUT"))return "Abre Conector Tato e inicia sesión en la cuenta vinculada a esta biblioteca.";
        if(value.contains("E_OWNER_CONSENT_REQUIRED")||value.contains("E_OWNER_BINDING")||value.contains("Autoriza primero"))return "Vincula esta cuenta una vez desde Ajustes → Autorizar conexión. La autorización queda guardada.";
        if(value.contains("E_SOURCE_ACCOUNT_CHANGED")||value.contains("otra cuenta")||value.contains("respaldo antiguo"))return "Esta biblioteca conserva su cuenta original. Comprueba la cuenta del conector; tus datos y descargas se mantienen.";
        if(value.contains("Instala Conector"))return "Actualiza Conector Tato en este mismo usuario Android y vuelve a intentarlo.";
        return "No se pudo completar la lectura. Pulsa Reintentar para restablecer la conexión. Puedes continuar sin volver a vincular la cuenta.";
    }
}
