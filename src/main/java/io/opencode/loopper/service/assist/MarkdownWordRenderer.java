package io.opencode.loopper.service.assist;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.util.Units;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.ext.gfm.tables.*;
import org.springframework.stereotype.Component;

/** CommonMark to an offline Chinese report style. No HTML execution or remote image fetching. */
@Component
public class MarkdownWordRenderer {
    public record Result(byte[] bytes,List<String> limitations) { }
    public Result render(String markdown,Path root) {
        if(markdown.length()>500_000)throw new AssistFailure("WORD_CONTENT_LIMIT","Word 正文超过 50 万字符，请拆分产物");
        try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            styles(doc);doc.getProperties().getCoreProperties().setCreator("Loopper");
            Set<String> warnings=new LinkedHashSet<>();Node parsed=Parser.builder().extensions(List.of(TablesExtension.create())).build().parse(markdown);
            boundNodes(parsed);
            blocks(parsed,doc,root,warnings,0,"");doc.write(out);
            byte[] bytes=out.toByteArray();if(bytes.length>20*1024*1024)throw new AssistFailure("WORD_SIZE_LIMIT","生成文件超过 20 MiB，请减少图片");
            try(XWPFDocument reopened=new XWPFDocument(new ByteArrayInputStream(bytes))) {
                if(reopened.getBodyElements().isEmpty())throw new AssistFailure("WORD_EMPTY","Markdown 没有可生成的正文");
            }
            return new Result(bytes,List.copyOf(warnings));
        }catch(AssistFailure e){throw e;}catch(Exception e){throw new AssistFailure("WORD_GENERATION_FAILED","Word 无法生成，请检查 Markdown 和本地图片");}
    }
    private void blocks(Node parent,XWPFDocument doc,Path root,Set<String> warnings,int depth,String prefix) throws Exception {
        if(depth>32)throw new AssistFailure("WORD_NESTING_LIMIT","Markdown 嵌套超过 32 层，请简化结构");
        for(Node node=parent.getFirstChild();node!=null;node=node.getNext()) {
            if(node instanceof TableBlock) {table(node,doc,root,warnings);continue;}
            if(node instanceof BulletList || node instanceof OrderedList) {
                int index=node instanceof OrderedList list?list.getStartNumber():1;
                for(Node item=node.getFirstChild();item!=null;item=item.getNext())blocks(item,doc,root,warnings,depth+1,node instanceof OrderedList?(index++)+". ":"• ");
            }else if(node instanceof Paragraph || node instanceof Heading) {
                XWPFParagraph p=doc.createParagraph();p.setSpacingAfter(160);p.setIndentationLeft(Math.min(depth,8)*240);
                if(node instanceof Heading h){p.setStyle("Heading"+h.getLevel());p.getCTP().getPPr().addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(h.getLevel()-1));}
                if(!prefix.isEmpty())run(p,prefix,false,false,false);
                inline(node,p,root,warnings,node instanceof Heading,false,false,0);
            }else if(node instanceof FencedCodeBlock code)code(doc,code.getLiteral());
            else if(node instanceof IndentedCodeBlock code)code(doc,code.getLiteral());
            else if(node instanceof HtmlBlock)warnings.add("原始 HTML 已省略，请用 Markdown 表格或段落表示");
            else if(node instanceof ThematicBreak)run(doc.createParagraph(),"────────",false,false,false);
            else blocks(node,doc,root,warnings,depth+1,prefix);
        }
    }
    private void inline(Node parent,XWPFParagraph p,Path root,Set<String> warnings,boolean bold,boolean italic,boolean code,int depth) throws Exception {
        if(depth>32)throw new AssistFailure("WORD_NESTING_LIMIT","行内结构过深");
        for(Node n=parent.getFirstChild();n!=null;n=n.getNext()) {
            if(n instanceof Text t)run(p,t.getLiteral(),bold,italic,code);
            else if(n instanceof Code c)run(p,c.getLiteral(),bold,italic,true);
            else if(n instanceof SoftLineBreak || n instanceof HardLineBreak)p.createRun().addBreak();
            else if(n instanceof Image img)image(img,p,root);
            else if(n instanceof Link link) {
                String url=link.getDestination();
                if(!url.matches("(?i)^(https?://|mailto:|#).*")){warnings.add("非 HTTP／邮件链接已保留文字，未创建链接");inline(n,p,root,warnings,bold,italic,code,depth+1);}
                else {XWPFHyperlinkRun r=p.createHyperlinkRun(url);r.setText(plain(n));r.setFontFamily("宋体");r.setColor("245EA8");r.setUnderline(UnderlinePatterns.SINGLE);}
            }else if(n instanceof HtmlInline)warnings.add("行内 HTML 标记已省略");
            else inline(n,p,root,warnings,bold||n instanceof StrongEmphasis,italic||n instanceof Emphasis,code,depth+1);
        }
    }
    private void table(Node table,XWPFDocument doc,Path root,Set<String> warnings) throws Exception {
        List<Node> rows=new ArrayList<>();for(Node section=table.getFirstChild();section!=null;section=section.getNext())for(Node row=section.getFirstChild();row!=null;row=row.getNext())rows.add(row);
        if(rows.size()>2000)throw new AssistFailure("WORD_TABLE_LIMIT","表格超过 2000 行，请拆分");
        XWPFTable output=doc.createTable();output.setWidth("100%");
        for(int i=0;i<rows.size();i++) {XWPFTableRow row=i==0?output.getRow(0):output.createRow();int column=0;
            for(Node cell=rows.get(i).getFirstChild();cell!=null;cell=cell.getNext()) {
                if(column>=64)throw new AssistFailure("WORD_TABLE_LIMIT","表格超过 64 列，请拆分");
                XWPFTableCell target=column<row.getTableCells().size()?row.getCell(column):row.addNewTableCell();column++;
                inline(cell,target.getParagraphs().getFirst(),root,warnings,i==0,false,false,0);
            }
        }
    }
    private static void image(Image image,XWPFParagraph p,Path root) throws Exception {
        String destination=image.getDestination();Path path=AssistFiles.resolve(root,destination);
        byte[] bytes=AssistFiles.read(path,2*1024*1024);String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        if(p.getDocument().getAllPictures().stream().mapToLong(picture->picture.getData().length).sum()+bytes.length>16*1024*1024)
            throw new AssistFailure("WORD_IMAGE_LIMIT","文档图片总量超过 16 MiB，请缩小图片");
        int type=name.endsWith(".png")?org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG:name.endsWith(".jpg")||name.endsWith(".jpeg")?org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG:-1;
        if(type<0)throw new AssistFailure("WORD_IMAGE_UNSUPPORTED","仅支持工作区中的 PNG／JPEG 图片，不读取远程图片");
        int width,height;
        try(var input=javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw new AssistFailure("WORD_IMAGE_INVALID","图片内容无法识别");
            var reader=readers.next();try{reader.setInput(input);width=reader.getWidth(0);height=reader.getHeight(0);}finally{reader.dispose();}
        }
        if(width<1||height<1||(long)width*height>16_000_000)throw new AssistFailure("WORD_IMAGE_LIMIT","图片像素超过上限，请缩小图片");
        double scale=Math.min(1,Math.min(450.0/width,600.0/height));
        try(var input=new ByteArrayInputStream(bytes)){p.createRun().addPicture(input,type,path.getFileName().toString(),Units.toEMU(width*scale),Units.toEMU(height*scale));}
    }
    private static void styles(XWPFDocument doc) {
        var styles=doc.createStyles();
        for(int level=1;level<=6;level++) {
            var style=org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle.Factory.newInstance();style.setStyleId("Heading"+level);
            style.setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType.PARAGRAPH);style.addNewName().setVal("heading "+level);
            style.addNewPPr().addNewOutlineLvl().setVal(java.math.BigInteger.valueOf(level-1));var properties=style.addNewRPr();properties.addNewB();
            var fonts=properties.addNewRFonts();fonts.setEastAsia("黑体");fonts.setAscii("Arial");
            properties.addNewSz().setVal(java.math.BigInteger.valueOf(level==1?36:level==2?30:24));styles.addStyle(new XWPFStyle(style));
        }
    }
    private static void boundNodes(Node root) {
        Deque<Node> pending=new ArrayDeque<>();pending.add(root);int count=0,images=0;
        while(!pending.isEmpty()) {
            Node node=pending.removeFirst();if(++count>20000||node instanceof Image&&++images>32)throw new AssistFailure("WORD_CONTENT_LIMIT","Markdown 结构或图片过多，请拆分文档（最多 32 张图片）");
            for(Node child=node.getFirstChild();child!=null;child=child.getNext())pending.addLast(child);
        }
    }
    private static String plain(Node n){StringBuilder result=new StringBuilder();for(Node c=n.getFirstChild();c!=null;c=c.getNext()){if(c instanceof Text t)result.append(t.getLiteral());else if(c instanceof Code t)result.append(t.getLiteral());else result.append(plain(c));}return result.toString();}
    private static void code(XWPFDocument doc,String text){for(String line:text.split("\n",-1))run(doc.createParagraph(),line,false,false,true);}
    private static void run(XWPFParagraph p,String text,boolean bold,boolean italic,boolean code){XWPFRun r=p.createRun();boolean heading=p.getStyle()!=null&&p.getStyle().startsWith("Heading");r.setFontFamily(code?"Consolas":heading?"黑体":"宋体");r.setFontSize(heading?"Heading1".equals(p.getStyle())?18:14:11);r.setBold(bold);r.setItalic(italic);r.setText(text);}
}
