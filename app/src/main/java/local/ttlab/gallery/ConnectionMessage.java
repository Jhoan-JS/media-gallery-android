package local.ttlab.gallery;

/** Consent loss is different from a cold connector, changed selection or interrupted read. */
final class ConnectionMessage {
    private static final java.util.regex.Pattern TELEGRAM_WAIT=java.util.regex.Pattern.compile("\\bE_TELEGRAM_FLOOD_WAIT_([0-9]{1,9})\\b");
    static long telegramWaitSeconds(Throwable failure){
        for(int depth=0;failure!=null&&depth<8;depth++,failure=failure.getCause()){
            java.util.regex.Matcher wait=TELEGRAM_WAIT.matcher(String.valueOf(failure.getMessage()));
            if(wait.find())return Long.parseLong(wait.group(1));
        }
        return 0;
    }
    static String telegramWaitMessage(long seconds){
        return "Telegram solicita esperar "+seconds+" segundos. Inténtalo cuando termine la espera.";
    }
    static String describe(Throwable error,boolean owner,String source){
        if(SourceId.local(source))return "No se pudo abrir la copia cifrada de este archivo.";
        if(!"telegram".equals(source))return describe(error,owner);
        long wait=telegramWaitSeconds(error);
        if(wait>0)return telegramWaitMessage(wait);
        StringBuilder codes=new StringBuilder();for(int n=0;error!=null&&n<8;n++,error=error.getCause())codes.append(error.getMessage()).append(' ');
        String value=codes.toString();
        String settings=BuildConfig.ISOLATED_CLIENT?"Fuentes → Telegram → Configurar conexión":"Conector TG";
        if(value.contains("E_TELEGRAM_LOGIN_REQUIRED"))return "Abre "+settings+" e inicia sesión en la cuenta vinculada a esta biblioteca.";
        if(value.contains("E_TELEGRAM_API_CONFIG")||value.contains("E_TELEGRAM_SETUP_REQUIRED"))return "Abre "+settings+" para configurar tus credenciales de aplicación de Telegram.";
        if(value.contains("E_TELEGRAM_CONSENT_REQUIRED"))return "En Fuentes, abre Configurar conexión y pulsa Autorizar conexión. La autorización de Telegram queda guardada.";
        if(value.contains("E_SOURCE_ACCOUNT_CHANGED")||value.contains("otra cuenta")||value.contains("respaldo antiguo"))return "Esta biblioteca conserva su cuenta original de Telegram. Comprueba la cuenta en "+settings+".";
        if(value.contains("E_MEDIA_CHANGED"))return "El archivo cambió en Telegram. Actualiza ese chat para obtener la versión vigente.";
        if(value.contains("E_TELEGRAM_MEDIA_UNAVAILABLE")||value.contains("E_TELEGRAM_PROTECTED_CONTENT"))return "Telegram ya no permite leer este archivo desde el chat seleccionado.";
        if(value.contains("Instala Conector")||value.contains("E_TELEGRAM_ENGINE_UNAVAILABLE")||value.contains("E_ISOLATED_CONNECTOR"))return "Instala o actualiza "+(BuildConfig.ISOLATED_CLIENT?"Conector de fuentes":"Conector TG")+" en este perfil y vuelve a intentarlo.";
        if(value.contains("E_TELEGRAM_UNMAPPED_PEER"))return "Este chat conserva sus copias locales. Selecciónalo de nuevo en "+settings+" para reconectar. Los respaldos antiguos sin identidad de chat solo se consultan sin conexión.";
        if(value.contains("E_TELEGRAM_NOT_SELECTED")||value.contains("E_TELEGRAM_CHAT_NOT_SELECTED"))return "Elige los chats que quieres explorar en "+settings+".";
        return "No se pudo completar la lectura de Telegram. Pulsa Reintentar para restablecer la conexión; la autorización se conserva.";
    }
    static String describe(Throwable error,boolean owner){
        if(!owner)return "Abre el clon de laboratorio con TestTes en este perfil y vuelve a intentarlo.";
        StringBuilder codes=new StringBuilder();for(int n=0;error!=null&&n<8;n++,error=error.getCause())codes.append(error.getMessage()).append(' ');
        String value=codes.toString();
        String connector=BuildConfig.ISOLATED_CLIENT?"Conector de fuentes":"Conector Tato";
        if(value.contains("E_ACCOUNT_SELECTION"))return "Selecciona en "+connector+" la cuenta ya vinculada. La autorización sigue guardada.";
        if(value.contains("E_LOGGED_OUT"))return "Abre "+connector+" e inicia sesión en la cuenta vinculada a esta biblioteca.";
        if(value.contains("E_OWNER_CONSENT_REQUIRED")||value.contains("E_OWNER_BINDING")||value.contains("Autoriza primero"))return "En Fuentes, abre Configurar conexión y pulsa Autorizar conexión. La autorización queda guardada.";
        if(value.contains("E_SOURCE_ACCOUNT_CHANGED")||value.contains("otra cuenta")||value.contains("respaldo antiguo"))return "Esta biblioteca conserva su cuenta original. Comprueba la cuenta del conector; tus datos y descargas se mantienen.";
        if(value.contains("Instala Conector"))return "Actualiza "+connector+" en este mismo usuario Android y vuelve a intentarlo.";
        return "No se pudo completar la lectura. Pulsa Reintentar para restablecer la conexión. Puedes continuar sin volver a vincular la cuenta.";
    }
}
