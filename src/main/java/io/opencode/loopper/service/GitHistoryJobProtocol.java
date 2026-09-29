package io.opencode.loopper.service;

import io.opencode.loopper.template.TemplateDateRange;
import io.opencode.loopper.template.TemplateGitEvidence;
import java.io.*;
import java.time.Clock;

/** Frozen branch and civil dates; no credentials, source URLs, model instructions or execution state. */
public final class GitHistoryJobProtocol {
    private GitHistoryJobProtocol() { }
    public record Input(GitSnapshotJobProtocol.Input source,String startDate,String endDate) {
        public Input {
            if(source==null||startDate==null||endDate==null||!startDate.matches("\\d{4}-\\d{2}-\\d{2}")
                    ||!endDate.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException("Explicit history dates required");
            TemplateDateRange.parse(startDate,endDate,Clock.systemUTC());
        }
        public TemplateDateRange dates(){return TemplateDateRange.parse(startDate,endDate,Clock.systemUTC());}
        public String nodeId(){return source.nodeId();}
    }
    public record Frozen(GitSnapshotJobProtocol.Binding binding,TemplateGitEvidence evidence) { }
    public static byte[] input(Input value)throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(1);byte[] source=GitSnapshotJobProtocol.input(value.source());
            out.writeInt(source.length);out.write(source);out.writeUTF(value.startDate());out.writeUTF(value.endDate());
        }
        return bytes.toByteArray();
    }
    public static Input input(byte[] bytes)throws IOException {
        if(bytes.length>32768)throw new IOException("History input bound");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=1)throw new IOException("History input version");
            int length=in.readInt();if(length<0||length>32768)throw new IOException("History source bound");
            byte[] source=in.readNBytes(length);if(source.length!=length)throw new EOFException();
            var value=new Input(GitSnapshotJobProtocol.input(source),in.readUTF(),in.readUTF());
            if(in.available()!=0)throw new IOException("History input extra fields");return value;
        }
    }
    public static void requireBinding(Input input,String sha,Frozen frozen)throws IOException {
        var value=frozen.evidence();var binding=frozen.binding();
        if(!binding.inputSha256().equals(sha)||!value.version().equals(TemplateGitEvidence.VERSION)
                ||!value.head().equals(binding.commit())||!value.branchId().equals(input.source().selection().id())
                ||!value.startDate().equals(input.startDate())||!value.endDate().equals(input.endDate())
                ||!value.timezone().equals(TemplateDateRange.ZONE.getId()))throw new IOException("History evidence binding differs");
    }
}
