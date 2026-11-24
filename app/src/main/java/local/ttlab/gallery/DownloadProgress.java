package local.ttlab.gallery;

final class DownloadProgress {
    static int percent(long bytes,long total){return total<=0?0:(int)Math.min(100,Math.max(0,bytes)*100d/total);}
    static long resumeBytes(long bytes,long total){return total<=0?0:bytes>=total?total:Math.max(0,bytes)/131072*131072;}
    static long remainingSeconds(long bytes,long total,long startBytes,long elapsedMs){
        if(total>0&&bytes>=total)return 0;
        if(total<=0||bytes<=startBytes||elapsedMs<=0)return -1;
        return (long)Math.ceil((total-bytes)*(elapsedMs/1000d)/(bytes-startBytes));
    }
    static String bytes(long bytes){
        double value=Math.max(0,bytes);String[] units={"B","KiB","MiB","GiB"};int unit=0;
        while(value>=1024&&unit<units.length-1){value/=1024;unit++;}
        return unit==0?((long)value)+" B":String.format(java.util.Locale.ROOT,"%.1f %s",value,units[unit]);
    }
    static String remainingLabel(long seconds){
        if(seconds<0)return "Calculando tiempo restante";
        if(seconds<60)return "Menos de 1 min restante";
        if(seconds<3600)return ((seconds+59)/60)+" min restantes";
        return seconds/3600+" h "+(seconds%3600)/60+" min restantes";
    }
}
