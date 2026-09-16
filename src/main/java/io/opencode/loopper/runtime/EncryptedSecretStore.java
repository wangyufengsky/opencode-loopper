package io.opencode.loopper.runtime;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Shared file encryption mechanics; callers own separate keys, directories and error contracts. */
public final class EncryptedSecretStore {
    private final Path directory;
    private final Path keyFile;
    private final String environmentKey;
    public EncryptedSecretStore(Path directory, Path keyFile, String environmentKey) {
        this.directory = directory.toAbsolutePath().normalize();
        this.keyFile = keyFile.toAbsolutePath().normalize();
        this.environmentKey = environmentKey;
    }
    public synchronized String save(String password) {
        if(password==null || password.length()>4096) throw failure();
        try {
            secureDirectory(directory); byte[] key=key(true); byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
            String id=UUID.randomUUID().toString(); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] bytes=new byte[nonce.length+encrypted.length]; System.arraycopy(nonce,0,bytes,0,12);
            System.arraycopy(encrypted,0,bytes,12,encrypted.length); writePrivate(directory.resolve(id),bytes);
            Arrays.fill(key,(byte)0); return id;
        } catch(Exception e) { throw failure(); }
    }
    public synchronized String read(String reference) {
        try {
            if(reference==null || !reference.matches("[0-9a-f-]{36}")) throw failure();
            Path file=directory.resolve(reference); requireRegular(file); byte[] bytes=Files.readAllBytes(file);
            if(bytes.length<28 || bytes.length>20000) throw failure();
            byte[] key=key(false); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,bytes,0,12));
            cipher.updateAAD(reference.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] decoded=cipher.doFinal(bytes,12,bytes.length-12); Arrays.fill(key,(byte)0);
            String password=new String(decoded,java.nio.charset.StandardCharsets.UTF_8); Arrays.fill(decoded,(byte)0); return password;
        } catch(Exception e) { throw failure(); }
    }
    private byte[] key(boolean create) throws Exception {
        if(environmentKey!=null && !environmentKey.isBlank()) {
            byte[] decoded=Base64.getDecoder().decode(environmentKey); if(decoded.length!=32) throw failure(); return decoded;
        }
        if(Files.exists(keyFile,LinkOption.NOFOLLOW_LINKS)) { requireRegular(keyFile); protect(keyFile,false);
            byte[] bytes=Files.readAllBytes(keyFile); if(bytes.length!=32) throw failure(); return bytes; }
        boolean hasSecrets;
        try(var stream=Files.list(directory)) { hasSecrets=stream.findAny().isPresent(); }
        if(!create || hasSecrets) throw failure();
        secureDirectory(keyFile.getParent()); byte[] key=new byte[32]; new SecureRandom().nextBytes(key);
        try { writePrivate(keyFile,key); } catch(FileAlreadyExistsException race) { return key(false); }
        return key;
    }
    public static void secureDirectory(Path directory) throws Exception {
        for(Path part=directory;part!=null;part=part.getParent()) if(Files.isSymbolicLink(part)) throw failure();
        Files.createDirectories(directory); protect(directory,true);
    }
    public static void requireRegular(Path file) throws Exception {
        for(Path part=file;part!=null;part=part.getParent()) if(Files.isSymbolicLink(part)) throw failure();
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw failure();
    }
    static void writePrivate(Path target,byte[] bytes) throws Exception {
        Path temporary=Files.createTempFile(target.getParent(),".secret-",".tmp");
        try { protect(temporary,false); Files.write(temporary,bytes);
            try { Files.createLink(target,temporary); }
            catch(UnsupportedOperationException e) { Files.move(temporary,target); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void protect(Path path,boolean directory) throws Exception {
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null) { posix.setPermissions(PosixFilePermissions.fromString(directory?"rwx------":"rw-------")); return; }
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl==null) throw failure();
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
    private static IllegalStateException failure() {
        return new IllegalStateException("Encrypted credential storage unavailable");
    }
}
