package local.ttlab.gallery;
final class PinAttempts {
 private static final String SECRET="pin-attempts";
 static synchronized long remaining(Vault vault)throws Exception{PinThrottle state=PinThrottle.read(vault.secret(SECRET));long wait=state.remaining(android.os.SystemClock.elapsedRealtime(),vault.bootCount);vault.secret(SECRET,state.bytes());return wait;}
 static synchronized boolean verify(Vault vault,char[] pin,byte[] verifier)throws Exception {
  PinThrottle attempts=PinThrottle.read(vault.secret(SECRET));long wait=attempts.remaining(android.os.SystemClock.elapsedRealtime(),vault.bootCount);
  if(wait>0){vault.secret(SECRET,attempts.bytes());throw new SecurityException("Espera "+((wait+999)/1000)+" segundos antes de reintentar.");}
  boolean valid=verifier!=null&&PinHash.matches(pin,verifier);
  if(valid)vault.secret(SECRET,new byte[0]);else{attempts.failed(android.os.SystemClock.elapsedRealtime(),vault.bootCount);vault.secret(SECRET,attempts.bytes());}
  return valid;
 }
}
