package io.opencode.loopper.service.assist;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Shared bounded file access; a caller still has to establish its business scope. */
public final class AssistFiles {
    private AssistFiles() { }
    public static String sha(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static Path resolve(Path root,String relative) {
        try {
            if(relative==null || relative.isBlank() || relative.length()>1024 || relative.contains("\\") || relative.contains(":"))throw denied();
            Path input=Path.of(relative);if(input.isAbsolute() || input.startsWith("..") || !input.normalize().equals(input))throw denied();
            Path canonical=root.toRealPath();Path result=canonical.resolve(input).normalize();
            if(!result.startsWith(canonical))throw denied();
            Path current=canonical;
            for(Path part:input) {
                String name=part.toString().toLowerCase(java.util.Locale.ROOT);
                if(name.startsWith(".") || name.endsWith(".key") || name.endsWith(".pem") || name.equals("credentials") || name.equals("secrets"))throw denied();
                current=current.resolve(part);if(Files.isSymbolicLink(current))throw denied();
            }
            return result;
        }catch(IOException|InvalidPathException e){throw denied();}
    }
    public static byte[] read(Path path,int limit) {
        try {
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>limit)throw new AssistFailure("DOCUMENT_SIZE_LIMIT","文件不存在或超过读取上限，请检查文件并缩小范围");
            try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new AssistFailure("DOCUMENT_SIZE_LIMIT","文件超过读取上限");return bytes;
            }
        }catch(IOException e){throw new AssistFailure("DOCUMENT_UNAVAILABLE","文件无法读取，请检查文件及访问权限");}
    }
    private static AssistFailure denied(){return new AssistFailure("DOCUMENT_PATH_FORBIDDEN","文件路径越界、包含敏感路径或符号链接，请使用作用域内的普通文件","REAUTHORIZE");}
}
