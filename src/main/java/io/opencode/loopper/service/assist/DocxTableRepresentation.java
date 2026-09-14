package io.opencode.loopper.service.assist;

import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.w3c.dom.*;

/** Preserves OOXML logical columns. HTML is inert source text, never executable markup. */
final class DocxTableRepresentation {
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int MAX_COLUMNS = 256, MAX_CELLS = 100_000, MAX_TEXT = 2_000_000;
    private DocxTableRepresentation() { }

    static String render(XWPFTable table) {
        List<List<Cell>> rows = new ArrayList<>();
        Map<Integer, Cell> previous = Map.of();
        int width = 0, count = 0, length = 0;
        Node grid = child(table.getCTTbl().getDomNode(), "tblGrid");
        for (Node item = grid == null ? null : grid.getFirstChild(); item != null; item = item.getNextSibling())
            if (WORD.equals(item.getNamespaceURI()) && "gridCol".equals(item.getLocalName())) width++;
        if (width > MAX_COLUMNS) throw bad("DOCX 表格超过 256 个逻辑列，请拆分文档");
        boolean complex = false;
        for (var row : table.getRows()) {
            if (Thread.currentThread().isInterrupted()) throw bad("解析已取消，请缩小文档后重试");
            if (rows.size() >= MAX_CELLS) throw bad("DOCX 表格行数超限，请拆分文档");
            Node props = child(row.getCtRow().getDomNode(), "trPr");
            int column = number(child(props, "gridBefore"), 0, 0);
            int after = number(child(props, "gridAfter"), 0, 0);
            complex |= column > 0 || after > 0;
            List<Cell> cells = new ArrayList<>();
            Map<Integer, Cell> current = new HashMap<>();
            for (var source : row.getTableCells()) {
                if (++count > MAX_CELLS) throw bad("DOCX 表格单元格超限，请拆分文档");
                if (!source.getTables().isEmpty()) throw bad("DOCX 嵌套表格暂不支持，请展开为独立表格");
                Node cellProps = child(source.getCTTc().getDomNode(), "tcPr");
                if (child(cellProps, "hMerge") != null) throw bad("DOCX 旧式横向合并暂不支持，请另存为使用 gridSpan 的 DOCX");
                int span = number(child(cellProps, "gridSpan"), 1, 1);
                if (column + span > MAX_COLUMNS) throw bad("DOCX 表格超过 256 个逻辑列，请拆分文档");
                String text = source.getText();
                length += text.length();
                if (length > MAX_TEXT) throw bad("DOCX 表格提取内容超过 200 万字符，请拆分文档");
                Node merge = child(cellProps, "vMerge");
                Cell cell = cell(rows.size(), column, span, text, merge, previous);
                if (cell.row == rows.size()) cells.add(cell);
                if (merge != null) current.put(column, cell);
                complex |= span > 1 || merge != null;
                column += span;
            }
            if (column + after > MAX_COLUMNS) throw bad("DOCX 表格超过 256 个逻辑列，请拆分文档");
            complex |= width != 0 && width != column + after;
            width = Math.max(width, column + after);
            rows.add(cells); previous = current;
        }
        return complex ? html(rows, width) : markdown(rows);
    }

    private static Cell cell(int row, int column, int span, String text, Node merge, Map<Integer, Cell> previous) {
        String mode = value(merge);
        if (merge == null || "restart".equals(mode)) return new Cell(row, column, span, text);
        if (!mode.isEmpty() && !"continue".equals(mode)) throw bad("DOCX 表格包含未知的纵向合并类型");
        Cell anchor = previous.get(column);
        if (anchor == null || anchor.span != span) throw bad("DOCX 纵向合并缺少匹配的起始单元格，请检查表格");
        if (!text.isBlank()) throw bad("DOCX 纵向合并延续格包含额外文字，请拆开合并后重试");
        anchor.height++;
        return anchor;
    }

    private static String html(List<List<Cell>> rows, int columns) {
        StringBuilder result = new StringBuilder("表格逻辑网格：" + rows.size() + " 行 × " + columns
                + " 列；data-row/data-column 从 1 开始，rowspan/colspan 表示合并范围。\n<table>\n");
        int[] occupiedUntil = new int[columns];
        for (int r = 0; r < rows.size(); r++) {
            var row = rows.get(r);
            append(result, "<tr>\n");
            int column = 0;
            for (var cell : row) {
                missing(result, r, column, cell.column, occupiedUntil);
                append(result, "<td data-row=\"" + (cell.row + 1) + "\" data-column=\""
                        + (cell.column + 1) + "\" rowspan=\"" + cell.height + "\" colspan=\"" + cell.span + "\">"
                        + escape(cell.text) + "</td>\n");
                Arrays.fill(occupiedUntil, cell.column, cell.column + cell.span, r + cell.height);
                column = cell.column + cell.span;
            }
            missing(result, r, column, columns, occupiedUntil);
            append(result, "</tr>\n");
        }
        append(result, "</table>\n\n");
        return result.toString();
    }

    private static void missing(StringBuilder out, int row, int from, int to, int[] occupiedUntil) {
        for (int c = from; c < to; c++) if (occupiedUntil[c] <= row)
            append(out, "<td data-row=\"" + (row + 1) + "\" data-column=\"" + (c + 1)
                    + "\" data-omitted=\"true\">原文此位置无单元格</td>\n");
    }

    private static String markdown(List<List<Cell>> rows) {
        StringBuilder result = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            append(result, "| " + String.join(" | ", rows.get(r).stream().map(c -> c.text.replace("|", "\\|")
                    .replace("\r", "").replace("\n", "<br>")).toList()) + " |\n");
            if (r == 0) append(result, "|" + " --- |".repeat(rows.get(r).size()) + "\n");
        }
        append(result, "\n"); return result.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("\r", "").replace("\n", "<br>");
    }
    private static void append(StringBuilder out, String value) {
        if (out.length() + value.length() > MAX_TEXT) throw bad("DOCX 表格表示超过 200 万字符，请拆分文档");
        out.append(value);
    }
    private static Node child(Node node, String name) {
        if (node == null) return null;
        for (Node item = node.getFirstChild(); item != null; item = item.getNextSibling())
            if (WORD.equals(item.getNamespaceURI()) && name.equals(item.getLocalName())) return item;
        return null;
    }
    private static String value(Node node) {
        if (node == null) return "";
        Node attr = node.getAttributes().getNamedItemNS(WORD, "val");
        return attr == null ? "" : attr.getNodeValue();
    }
    private static int number(Node node, int fallback, int minimum) {
        if (node == null) return fallback;
        try { int n = Integer.parseInt(value(node)); if (n >= minimum && n <= MAX_COLUMNS) return n; }
        catch (NumberFormatException ignored) { }
        throw bad("DOCX 表格列跨度无效或超过 256，请检查表格");
    }
    private static AssistFailure bad(String message) { return new AssistFailure("DOCUMENT_READ_FAILED", message); }
    private static final class Cell {
        final int row, column, span;
        final String text;
        int height = 1;
        Cell(int row, int column, int span, String text) { this.row = row; this.column = column; this.span = span; this.text = text; }
    }
}
