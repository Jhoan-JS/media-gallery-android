package local.ttlab.gallery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CombinedSearch {
    static final int PAGE_SIZE=40;

    static final class Row {
        final String source,chat;
        final Item item;
        Row(String source,Item item,String chat){this.source=source;this.item=item;this.chat=chat;}
    }
    static final class Key {
        final String source;
        final int channel,id;
        final long date;
        Key(Row row){source=row.source;channel=row.item.channel;id=row.item.id;date=row.item.date;}
    }
    static final class Page {
        final List<Row> rows;
        final Key next;
        Page(List<Row> rows,Key next){this.rows=rows;this.next=next;}
    }
    private static final Comparator<Row> ORDER=Comparator.comparingLong((Row row)->row.item.date).reversed()
        .thenComparing(Comparator.comparingInt((Row row)->row.item.id).reversed())
        .thenComparingInt(row->row.item.channel).thenComparing(row->row.source);

    static Page merge(List<Row> tato,List<Row> telegram){
        List<Row> rows=new ArrayList<>(tato.size()+telegram.size());
        rows.addAll(tato);rows.addAll(telegram);rows.sort(ORDER);
        if(rows.size()<=PAGE_SIZE)return new Page(rows,null);
        return new Page(new ArrayList<>(rows.subList(0,PAGE_SIZE)),new Key(rows.get(PAGE_SIZE-1)));
    }
}
