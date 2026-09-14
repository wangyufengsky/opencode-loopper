package io.opencode.loopper.service.assist;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Offline deterministic document representations. Never evaluates formulas, macros or external links. */
@Component
public class AssistDocumentParser {
    public static final String VERSION="ASSIST_DOCUMENT_V2";
    public record Section(String id,String title,String markdown) { }
    public record Document(String format,List<Section> sections,List<String> limitations) { }
    public Document parse(String name,byte[] bytes) {
        if(bytes.length==0 || bytes.length>20L*1024*1024) throw bad("文档为空或超过 20 MiB");
        String format=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        try {
            if(Set.of("docx","xlsx","pptx").contains(format)) validateZip(bytes);
            List<Section> sections=switch(format) {
                case "md","markdown" -> markdown(bytes);
                case "docx" -> docx(bytes);
                case "xlsx" -> xlsx(bytes);
                case "pptx" -> pptx(bytes);
                case "pdf" -> pdf(bytes);
                default -> throw bad("仅支持 DOCX、XLSX、PPTX、文本 PDF 和 Markdown");
            };
            if(sections.stream().allMatch(s->s.markdown().isBlank())) throw bad("没有提取到可读文本；扫描件需先在内网完成 OCR");
            if(sections.stream().mapToLong(s->s.markdown().length()).sum()>2_000_000)throw bad("提取内容超过 200 万字符，请拆分文档");
            List<Section> numbered=new ArrayList<>();add(numbered,sections);
            return new Document(format,List.copyOf(numbered),List.of("仅提供可提取内容，不还原版式、动画或图片中的文字；不执行公式或抓取外部资源"));
        } catch(AssistFailure failure) {throw failure;}
        catch(Exception failure) {throw bad("文档无法解析，请检查格式、密码保护和文件完整性");}
    }
    private static List<Section> markdown(byte[] bytes) throws Exception {
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        List<Section> sections=new ArrayList<>();String title="正文";StringBuilder body=new StringBuilder();boolean fenced=false;
        for(String line:text.split("(?<=\\n)",-1)) {
            String trimmed=line.stripLeading();
            if(trimmed.startsWith("```")||trimmed.startsWith("~~~"))fenced=!fenced;
            if(!fenced&&line.matches("#{1,6}\\s+[^\\n]+\\n?")) {
                if(!body.isEmpty()){add(sections,chunks(title,body.toString()));body.setLength(0);}title=line.replaceFirst("^#{1,6}\\s+","").strip();
            }
            append(body,line);
        }
        if(!body.isEmpty())add(sections,chunks(title,body.toString()));return sections;
    }
    private static List<Section> docx(byte[] bytes) throws Exception {
        try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder text=new StringBuilder();List<Section> sections=new ArrayList<>();String title="正文";
            for(IBodyElement element:doc.getBodyElements()) {
                if(element instanceof XWPFParagraph p) {
                    String style=p.getStyle(); String prefix=style!=null && style.matches("(?i)heading[1-6]")
                            ?"#".repeat(Integer.parseInt(style.substring(style.length()-1)))+" ":"";
                    if(!prefix.isEmpty()){if(!text.isEmpty()){add(sections,chunks(title,text.toString()));text.setLength(0);}title=p.getText();}
                    append(text,prefix+p.getText()+"\n\n");
                } else if(element instanceof XWPFTable table) {
                    append(text,DocxTableRepresentation.render(table));
                }
            }
            if(!text.isEmpty())add(sections,chunks(title,text.toString()));return sections;
        }
    }
    private static List<Section> xlsx(byte[] bytes) throws Exception {
        List<Section> sections=new ArrayList<>(); int cells=0;
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if(book.getNumberOfSheets()>128) throw bad("工作表超过 128 个，请拆分文件");
            DataFormatter formatter=new DataFormatter(Locale.ROOT); formatter.setUseCachedValuesForFormulaCells(true);
            for(Sheet sheet:book) {
                StringBuilder text=new StringBuilder("| 单元格 | 类型 | 显示／缓存值 | 公式 |\n| --- | --- | --- | --- |\n");
                for(Row row:sheet) for(Cell cell:row) {
                    if(++cells>100000) throw bad("单元格超过 100000 个，请缩小工作簿");
                    String formula=cell.getCellType()==CellType.FORMULA?cell.getCellFormula():"";
                    append(text,"| "+cell.getAddress()+" | "+cell.getCellType()+" | "+escape(formatter.formatCellValue(cell))
                            +" | "+escape(formula)+" |\n");
                }
                add(sections,chunks(sheet.getSheetName(),text.toString()));
            }
        }
        return sections;
    }
    private static List<Section> pptx(byte[] bytes) throws Exception {
        List<Section> sections=new ArrayList<>();
        try(XMLSlideShow ppt=new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            if(ppt.getSlides().size()>1000) throw bad("幻灯片超过 1000 页，请拆分文件");
            int page=0;
            for(XSLFSlide slide:ppt.getSlides()) {StringBuilder text=new StringBuilder();
                shapes(slide.getShapes(),text,0);add(sections,chunks("第 "+(++page)+" 页",text.toString()));}
        }
        return sections;
    }
    private static void shapes(List<XSLFShape> shapes,StringBuilder text,int depth) {
        if(depth>10 || shapes.size()>10000) throw bad("幻灯片形状过多或嵌套过深");
        for(XSLFShape shape:shapes) {
            if(shape instanceof XSLFTextShape s) append(text,s.getText()+"\n\n");
            else if(shape instanceof XSLFTable table) for(var row:table.getRows()) {
                append(text,"| "+String.join(" | ",row.getCells().stream().map(c->escape(c.getText())).toList())+" |\n");
            } else if(shape instanceof XSLFGroupShape group) shapes(group.getShapes(),text,depth+1);
        }
    }
    private static List<Section> pdf(byte[] bytes) throws Exception {
        List<Section> sections=new ArrayList<>();
        try(var pdf=Loader.loadPDF(bytes)) {
            if(pdf.isEncrypted()) throw bad("不支持加密 PDF，请先提供未加密副本");
            if(pdf.getNumberOfPages()>1000) throw bad("PDF 超过 1000 页，请拆分文件");
            PDFTextStripper reader=new PDFTextStripper();
            for(int p=1;p<=pdf.getNumberOfPages();p++) {reader.setStartPage(p);reader.setEndPage(p);add(sections,chunks("第 "+p+" 页",reader.getText(pdf)));}
        }
        return sections;
    }
    private static void validateZip(byte[] bytes) throws Exception {
        int count=0; long total=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null) {
                if(++count>10000 || entry.getName().toLowerCase(Locale.ROOT).contains("vbaproject")) throw bad("Office 容器过大或含有宏");
                int n;while((n=zip.read(buffer))!=-1) {total+=n;if(total>100L*1024*1024) throw bad("Office 解压内容超过 100 MiB");}
            }
        }
        if(count==0) throw bad("不是有效的 Office 容器");
    }
    private static List<Section> chunks(String title,String text) {
        List<Section> result=new ArrayList<>();
        for(int start=0;start<text.length();) {
            int end=Math.min(text.length(),start+12000);
            if(end<text.length() && Character.isHighSurrogate(text.charAt(end-1)))end--;
            result.add(new Section("",title,text.substring(start,end)));start=end;
        }
        if(result.isEmpty())result.add(new Section("",title,"")); return result;
    }
    private static void add(List<Section> target,List<Section> source) {
        for(Section part:source) {if(target.size()>=2048)throw bad("提取内容超过分段上限，请拆分文档");
            target.add(new Section(Integer.toString(target.size()),part.title(),part.markdown()));}
    }
    private static void append(StringBuilder target,String text) {
        if(Thread.currentThread().isInterrupted())throw bad("解析已取消，请缩小文档后重试");
        if(target.length()+text.length()>2_000_000)throw bad("单个文档部分超过 200 万字符，请拆分文件");target.append(text);
    }
    private static String escape(String text) {return text.replace("|","\\|").replace("\r","").replace("\n","<br>");}
    private static AssistFailure bad(String detail) {return new AssistFailure("DOCUMENT_READ_FAILED",detail);}
}
