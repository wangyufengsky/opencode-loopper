package io.opencode.loopper.service.ppt;

import io.opencode.loopper.config.LoopperProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Application-owned paths only. Never accepts an absolute or user-provided source path. */
@Component
public class PptStorage {
    private final Path root;
    public PptStorage(LoopperProperties properties) {
        Path configured=properties.getDataDir().toAbsolutePath().normalize(),ancestor=configured;
        while(ancestor!=null&&!Files.exists(ancestor))ancestor=ancestor.getParent();
        try { root=(ancestor==null?configured:ancestor.toRealPath().resolve(ancestor.relativize(configured))).resolve("ppt"); }
        catch(IOException failure){throw PptSupport.bad("PPT_STORAGE_UNAVAILABLE","数据目录无法读取，请检查目录权限");}
    }
    public Path workspace(String document) { return prepare(document,"session").getParent(); }
    public Path prepare(String document,String key) {
        Path path=path(document,key);
        try {Files.createDirectories(path.getParent());check(path);return path;}
        catch(IOException failure){throw unavailable();}
    }
    public Path path(String document,String key) {
        if(document==null||!document.matches("[A-Za-z0-9_-]{1,100}")||key==null||!key.matches("[A-Za-z0-9_./-]{1,240}"))throw denied();
        Path base=root.resolve(document),path=base.resolve(key).normalize();
        if(!path.startsWith(base)||path.equals(base))throw denied();check(path);return path;
    }
    public void write(String document,String key,byte[] bytes) {
        Path target=prepare(document,key);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            if(!PptSupport.hash(read(document,key,bytes.length)).equals(PptSupport.hash(bytes)))throw PptSupport.bad("PPT_FILE_CHANGED","已有版本文件校验失败，请保留现场后重新制作");
            return;
        }
        Path temp=target.resolveSibling(target.getFileName()+"."+UUID.randomUUID()+".part");
        try {
            Files.write(temp,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
            check(target);Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException failure){throw unavailable();}
    }
    public byte[] read(String document,String key,long maximum) {
        Path path=path(document,key);
        try {
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>maximum)throw unavailable();
            try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=in.readNBytes((int)Math.min(maximum+1,Integer.MAX_VALUE));if(bytes.length>maximum)throw unavailable();return bytes;
            }
        }catch(IOException failure){throw unavailable();}
    }
    public byte[] verified(String document,String key,long length,String hash) {
        byte[] bytes=read(document,key,length);
        if(bytes.length!=length||!PptSupport.hash(bytes).equals(hash))throw PptSupport.bad("PPT_FILE_CHANGED","文件哈希校验失败，请重新上传原资料或重新生成制品");return bytes;
    }
    private void check(Path path) {for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw denied();}
    private RuntimeException denied(){return PptSupport.bad("PPT_PATH_DENIED","PPT 受管目录不能包含符号链接或越界路径");}
    private RuntimeException unavailable(){return PptSupport.bad("PPT_STORAGE_UNAVAILABLE","PPT 文件不可读或无法保存，请检查数据目录权限和剩余空间后重试");}
}
