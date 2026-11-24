package local.ttlab.gallery;
import java.text.DateFormatSymbols;
import java.util.Arrays;
/** Immutable monthly offsets; no catalogue rows or database work on the scroll thread. */
final class DateSections {
 static final DateSections EMPTY=new DateSections(new String[0],new int[0]);
 final String[] labels;final int[] starts;final int total;
 DateSections(String[] months,int[] counts){
  if(months==null||counts==null||months.length!=counts.length)throw new IllegalArgumentException("Índice mensual inválido");
  labels=new String[months.length];starts=new int[months.length];long sum=0;String[] names=DateFormatSymbols.getInstance().getShortMonths();
  for(int n=0;n<counts.length;n++){if(counts[n]<=0||months[n]==null||sum+counts[n]>Integer.MAX_VALUE)throw new IllegalArgumentException("Índice mensual inválido");starts[n]=(int)sum;sum+=counts[n];String m=months[n];int month=m.matches("[0-9]{4}-[0-9]{2}")?Integer.parseInt(m.substring(5)):0;labels[n]=month>=1&&month<=12?names[month-1]+" "+m.substring(0,4):"Sin fecha";}total=(int)sum;
 }
 int position(int section){return starts.length==0?0:starts[Math.max(0,Math.min(section,starts.length-1))];}
 int section(int position){if(starts.length==0)return 0;int index=Arrays.binarySearch(starts,Math.max(0,position));return index>=0?index:Math.max(0,-index-2);}
}
