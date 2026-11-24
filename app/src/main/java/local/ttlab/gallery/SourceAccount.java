package local.ttlab.gallery;
/** Account identity is opaque and pinned; a new connection cannot replace catalogue ownership. */
final class SourceAccount {
    static String accept(String stored,String offered,boolean owner,boolean hasMedia){
        if(offered==null||!offered.matches("[0-9a-f]{64}")||(stored!=null&&!stored.matches("[0-9a-f]{64}")))throw new SecurityException("Identidad del conector inválida");
        if(stored!=null&&!stored.equals(offered))throw new SecurityException("Esta biblioteca pertenece a otra cuenta. Se conservan sus datos y descargas.");
        if(stored==null&&owner&&hasMedia)throw new SecurityException("El respaldo antiguo no identifica su cuenta. Se conserva para consulta local.");
        return offered;
    }
}
