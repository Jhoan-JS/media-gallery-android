package local.ttlab.telegram;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class BridgePolicy {
    static final int MAX_RANGE=524288;
    static boolean range(long offset,int length,long size) {
        return offset>=0&&length>0&&length<=MAX_RANGE&&size>=0&&offset<=size&&length<=size-offset;
    }
    static boolean account(String value) {return value!=null&&value.matches("[0-9a-f]{64}");}
    static boolean sameAccount(String expected,String offered) {
        return account(expected)&&account(offered)&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),offered.getBytes(StandardCharsets.US_ASCII));
    }
    static boolean decimal(String value,boolean signed) {
        if(value==null||!value.matches(signed?"-?[0-9]{1,19}":"[0-9]{1,19}"))return false;
        try {long parsed=Long.parseLong(value);return signed||parsed>0;}catch(NumberFormatException bad){return false;}
    }
    static String accessHash(String kind,String value){
        if("group".equals(kind)&&(value==null||value.isEmpty()))return "0";
        if(!decimal(value,true))throw new IllegalArgumentException("E_TELEGRAM_PROTOCOL_HASH");return value;
    }
    public static String error(Throwable failure) {
        String code=failure.getMessage();
        if(code==null)return "E_TELEGRAM_UNAVAILABLE";
        if(code.matches("E_[A-Z0-9_]{1,80}"))return code;
        if(code.matches("FLOOD_WAIT_[0-9]{1,9}"))return "E_TELEGRAM_"+code;
        switch(code) {
            case "AUTH_REQUIRED":return "E_TELEGRAM_LOGIN_REQUIRED";
            case "MEDIA_CHANGED":return "E_MEDIA_CHANGED";
            case "TIMEOUT":return "E_TELEGRAM_TIMEOUT";
            case "CANCELLED":return "E_TELEGRAM_CANCELLED";
            case "BAD_REQUEST":return "E_TELEGRAM_BAD_REQUEST";
            case "PASSWORD_REQUIRED":return "E_TELEGRAM_PASSWORD_REQUIRED";
            case "PHONE_CODE_INVALID":return "E_TELEGRAM_CODE_INVALID";
            case "PASSWORD_HASH_INVALID":return "E_TELEGRAM_PASSWORD_INVALID";
            case "ACCOUNT_CHANGED":return "E_SOURCE_ACCOUNT_CHANGED";
            case "BAD_LOCATOR":case "BAD_RANGE":case "SHORT_READ":case "PROTECTED_CONTENT":case "UNSUPPORTED_RESPONSE":case "RPC_ERROR":case "NETWORK_ERROR":case "REFERENCE_EXPIRED":case "MEDIA_UNAVAILABLE":return "E_TELEGRAM_"+code;
            case "LOCATION_INVALID":case "LIMIT_INVALID":case "OFFSET_INVALID":case "FILE_ID_INVALID":case "AUTH_BYTES_INVALID":case "DC_ID_INVALID":case "CONNECTION_NOT_INITED":case "CONNECTION_LAYER_INVALID":case "LAYER_INVALID":case "FILEREF_UPGRADE_NEEDED":return "E_TELEGRAM_"+code;
            default:return "E_TELEGRAM_UNAVAILABLE";
        }
    }
}
