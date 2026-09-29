package io.opencode.loopper.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Small immutable, credential-free input for the supervised push helper. */
public final class GitPushProtocol {
    private GitPushProtocol() { }
    public record Input(String id,String project,String repository,String gitDirectory,String remote,String url,String branch,String commit,String tree) {
        public Input {
            UUID.fromString(id);for(var path:List.of(project,repository,gitDirectory))if(!Path.of(path).isAbsolute())throw new IllegalArgumentException("Absolute Git identity required");
            GitPublicationTransport.name(remote);GitPublicationTransport.validateUrl(url);
            if(branch==null||!branch.matches("loopper/results/[a-z0-9-]{1,80}")||!objectId(commit)||!objectId(tree)||commit.length()!=tree.length())throw new IllegalArgumentException("Fixed push identity required");
        }
    }
    public static byte[] encode(Input input)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(1);for(var value:List.of(input.id(),input.project(),input.repository(),input.gitDirectory(),input.remote(),input.url(),input.branch(),input.commit(),input.tree())){byte[] field=value.getBytes(StandardCharsets.UTF_8);if(field.length>16384)throw new IOException("Push field limit");out.writeInt(field.length);out.write(field);}}return bytes.toByteArray();
    }
    public static Input decode(byte[] bytes)throws IOException {
        if(bytes.length>128*1024)throw new IOException("Push input limit");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=1)throw new IOException("Push input version");var fields=new ArrayList<String>();for(int i=0;i<9;i++){int size=in.readInt();if(size<0||size>16384)throw new IOException("Push field limit");byte[] field=in.readNBytes(size);if(field.length!=size)throw new EOFException();fields.add(new String(field,StandardCharsets.UTF_8));}if(in.available()!=0)throw new IOException("Unexpected push input");return new Input(fields.get(0),fields.get(1),fields.get(2),fields.get(3),fields.get(4),fields.get(5),fields.get(6),fields.get(7),fields.get(8));}
    }
    private static boolean objectId(String value){return value!=null&&value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})");}
}
