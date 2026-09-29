package io.opencode.loopper.service;

import java.util.*;

/** Shared bounded inventory of a committed tree. Exclusions remain visible in review evidence. */
public final class GitSnapshotInventory {
    private GitSnapshotInventory() { }
    public record Entry(String path,String blobSha,String mode,long sizeBytes,String limitation) { }
    public static List<Entry> parse(String output) {
        if(output==null||!output.isEmpty()&&!output.endsWith("\0"))throw invalid();
        var result=new ArrayList<Entry>();var paths=new HashSet<String>();
        for(String entry:output.split("\u0000",-1)) {
            if(entry.isEmpty())continue;
            int tab=entry.indexOf('\t');if(tab<0)throw invalid();
            String[] fields=entry.substring(0,tab).strip().split(" +");if(fields.length!=4)throw invalid();
            String path=entry.substring(tab+1),limitation=null;long size;
            boolean submodule=fields[0].equals("160000");
            if(path.isEmpty()||!(submodule?fields[1].equals("commit")&&fields[3].equals("-"):
                    Set.of("100644","100755","120000").contains(fields[0])&&fields[1].equals("blob")&&!fields[3].equals("-")))throw invalid();
            try{size=fields[3].equals("-")?0:Long.parseLong(fields[3]);}catch(NumberFormatException malformed){throw invalid();}
            if(size<0||!paths.add(path))throw invalid();
            if(!fields[0].equals("100644")&&!fields[0].equals("100755"))limitation="符号链接或子模块未展开";
            else if(protectedPath(path))limitation="受保护文件不提供读取";
            else if(size>2_000_000)limitation="文件超过单次源码读取上限";
            result.add(new Entry(path,objectId(fields[2]),fields[0],size,limitation));
            if(result.size()>50000)throw new BadRequestException("DOCUMENT_CODE_MANIFEST_LIMIT","冻结代码目录超过 50000 项，需缩小项目范围");
        }
        return List.copyOf(result);
    }
    public static boolean protectedPath(String path) {
        if(path.startsWith("/")||path.contains("\\")||path.chars().anyMatch(Character::isISOControl))return true;
        for(String part:path.split("/")) {
            String value=part.toLowerCase(Locale.ROOT);
            if(value.equals("..")||value.equals(".git")||value.equals(".ssh")||value.equals(".aws")
                    ||value.equals(".env")||value.startsWith(".env.")&&!value.equals(".env.example")
                    ||value.endsWith(".pem")||value.endsWith(".key")||value.equals("id_rsa")
                    ||value.equals("id_ed25519")||value.equals("credentials"))return true;
        }
        return false;
    }
    public static String objectId(String value) {
        if(value==null||!value.matches("[0-9a-f]{40}|[0-9a-f]{64}"))throw new BadRequestException("DOCUMENT_CODE_SHA_INVALID","提交身份无效");
        return value;
    }
    private static BadRequestException invalid(){return new BadRequestException("DOCUMENT_CODE_MANIFEST_INVALID","代码目录输出或元数据不完整");}
}
